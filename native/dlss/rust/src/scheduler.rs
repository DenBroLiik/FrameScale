//! Bounded, nonblocking Vulkan command/fence ring.
//! Completion of Vulkan work is NOT completion of an external GL consumer.
use crate::vulkan::DeviceContext;
use ash::vk;
use std::sync::atomic::{AtomicU64,Ordering};

const MAX_SLOTS:usize=4;
static NEXT_ID:AtomicU64=AtomicU64::new(1);
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub struct Ticket{owner:u64,index:usize,generation:u64}
impl Ticket{pub fn index(self)->usize{self.index}}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
enum State{Free,Recording,Submitted,Ready}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Poll{Pending,Ready}
#[derive(Debug,PartialEq,Eq)]
pub enum ScheduleError{Invalid,Stale,WrongState,Poisoned,Vulkan(i32)}
type Result<T>=std::result::Result<T,ScheduleError>;
struct Book{
    owner:u64,next:usize,slots:Vec<(u64,State)>,
}
impl Book{
    fn new(n:usize)->Result<Self>{
        if !(1..=MAX_SLOTS).contains(&n){return Err(ScheduleError::Invalid)}
        let owner=NEXT_ID.fetch_update(Ordering::Relaxed,Ordering::Relaxed,|v|v.checked_add(1))
            .map_err(|_|ScheduleError::Invalid)?;
        Ok(Self{owner,next:0,slots:vec![(0,State::Free);n]})
    }
    fn reserve(&mut self)->Result<Option<Ticket>>{
        let n=self.slots.len();
        for offset in 0..n{
            let i=(self.next+offset)%n;
            let (g,s)=&mut self.slots[i];
            if *s==State::Free{
                *g=g.checked_add(1).ok_or(ScheduleError::Invalid)?;
                *s=State::Recording;
                self.next=(i+1)%n;
                return Ok(Some(Ticket{owner:self.owner,index:i,generation:*g}))
            }
        }
        Ok(None)
    }
    fn state(&self,t:Ticket)->Result<State>{
        if t.owner!=self.owner{return Err(ScheduleError::Stale)}
        let (g,s)=self.slots.get(t.index).ok_or(ScheduleError::Stale)?;
        if *g!=t.generation||*s==State::Free{return Err(ScheduleError::Stale)}
        Ok(*s)
    }
    fn expect(&self,t:Ticket,s:State)->Result<()>{
        if self.state(t)?!=s{return Err(ScheduleError::WrongState)} Ok(())
    }
    fn transition(&mut self,t:Ticket,from:State,to:State)->Result<()>{
        self.expect(t,from)?;self.slots[t.index].1=to;Ok(())
    }
    fn idle(&self)->bool{self.slots.iter().all(|(_,s)|*s==State::Free)}
}
struct Slot{cmd:vk::CommandBuffer,fence:vk::Fence}
/// Owns one command pool and a fixed number of command buffers/fences.
/// Neither Send nor Sync (borrows the thread-bound DeviceContext).
/// Does not own images or NGX history; callers must retain them through release.
/// Explicit close required; Drop intentionally never destroys potentially live work.
#[must_use]
pub struct Scheduler<'a>{
    ctx:&'a DeviceContext,pool:vk::CommandPool,slots:Vec<Slot>,book:Book,poisoned:bool,
}
impl<'a> Scheduler<'a>{
    pub fn new(ctx:&'a DeviceContext,n:usize)->Result<Self>{
        let book=Book::new(n)?;
        let d=ctx.raw_device();
        let pi=vk::CommandPoolCreateInfo::default().queue_family_index(ctx.family())
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let pool=unsafe{d.create_command_pool(&pi,None)}.map_err(|e|ScheduleError::Vulkan(e.as_raw()))?;
        let mut slots=Vec::with_capacity(n);
        let result=(||{
            let ai=vk::CommandBufferAllocateInfo::default().command_pool(pool)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(n as u32);
            let cmds=unsafe{d.allocate_command_buffers(&ai)}?;
            for cmd in cmds{
                let fence=unsafe{d.create_fence(&vk::FenceCreateInfo::default(),None)}?;
                slots.push(Slot{cmd,fence});
            }
            Ok::<(),vk::Result>(())
        })();
        if let Err(e)=result{
            unsafe{for s in &slots{d.destroy_fence(s.fence,None)} d.destroy_command_pool(pool,None)}
            return Err(ScheduleError::Vulkan(e.as_raw()))
        }
        Ok(Self{ctx,pool,slots,book,poisoned:false})
    }
    fn live(&self)->Result<()>{if self.poisoned{Err(ScheduleError::Poisoned)}else{Ok(())}}
    fn check<T>(&mut self,r:std::result::Result<T,vk::Result>)->Result<T>{
        r.map_err(|e|{self.poisoned=true;ScheduleError::Vulkan(e.as_raw())})
    }
    /// No fence wait. None means Busy; caller decides how to skip/fallback.
    pub fn try_begin(&mut self)->Result<Option<(Ticket,vk::CommandBuffer)>>{
        self.live()?;
        let Some(t)=self.book.reserve()? else{return Ok(None)};
        let cmd=self.slots[t.index].cmd;
        let r=unsafe{self.ctx.raw_device().reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty())};
        self.check(r)?;
        let info=vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        let r=unsafe{self.ctx.raw_device().begin_command_buffer(cmd,&info)};
        self.check(r)?;
        Ok(Some((t,cmd)))
    }
    /// # Safety
    /// Exclusive queue access. Valid commands, resource lifetimes, barriers,
    /// and binary semaphore ordering. All referenced resources survive retirement.
    /// Semaphore waits must eventually be signaled; this is not checked by Vulkan.
    pub unsafe fn submit(&mut self,t:Ticket,waits:&[vk::Semaphore],stages:&[vk::PipelineStageFlags],
        signals:&[vk::Semaphore])->Result<()>{
        self.live()?;self.book.expect(t,State::Recording)?;
        if waits.len()!=stages.len()||waits.len()>8||signals.len()>8
            ||stages.iter().any(|s|s.is_empty())
            ||waits.iter().chain(signals).any(|s|*s==vk::Semaphore::null())
            ||waits.iter().enumerate().any(|(i,s)|waits[..i].contains(s)||signals.contains(s))
            ||signals.iter().enumerate().any(|(i,s)|signals[..i].contains(s)){
            return Err(ScheduleError::Invalid)
        }
        let (cmd,fence)=(self.slots[t.index].cmd,self.slots[t.index].fence);
        let d=self.ctx.raw_device();
        let r=unsafe{d.end_command_buffer(cmd)};self.check(r)?;
        let r=unsafe{self.ctx.raw_device().reset_fences(&[fence])};self.check(r)?;
        let cmds=[cmd];
        let info=vk::SubmitInfo::default().command_buffers(&cmds).wait_semaphores(waits)
            .wait_dst_stage_mask(stages).signal_semaphores(signals);
        let r=unsafe{self.ctx.raw_device().queue_submit(self.ctx.queue(),&[info],fence)};
        self.check(r)?;
        self.book.transition(t,State::Recording,State::Submitted)
    }
    /// Nonblocking vkGetFenceStatus. Ready still owns the slot.
    pub fn poll(&mut self,t:Ticket)->Result<Poll>{
        self.live()?;
        match self.book.state(t)?{
            State::Ready=>Ok(Poll::Ready),
            State::Submitted=>{
                let r=unsafe{self.ctx.raw_device().get_fence_status(self.slots[t.index].fence)};
                if self.check(r)?{
                    self.book.transition(t,State::Submitted,State::Ready)?;Ok(Poll::Ready)
                }else{Ok(Poll::Pending)}
            }
            _=>Err(ScheduleError::WrongState),
        }
    }
    /// # Safety
    /// Discard recorded commands, no pending work or stateful NGX evaluation;
    /// invalidate temporal history separately if needed.
    pub unsafe fn cancel(&mut self,t:Ticket)->Result<()>{
        self.live()?;self.book.expect(t,State::Recording)?;
        let r=unsafe{self.ctx.raw_device().reset_command_buffer(self.slots[t.index].cmd,vk::CommandBufferResetFlags::empty())};
        self.check(r)?;self.book.transition(t,State::Recording,State::Free)
    }
    /// # Safety
    /// Vulkan fence is complete AND all external GL/other consumers have retired.
    /// No resources from this ticket remain in use.
    pub unsafe fn release(&mut self,t:Ticket)->Result<()>{
        self.live()?;self.book.transition(t,State::Ready,State::Free)
    }
    pub fn idle(&self)->bool{!self.poisoned&&self.book.idle()}
    /// Does not wait. A busy/poisoned scheduler cannot be closed.
    pub fn close(&mut self)->Result<()>{
        if !self.idle(){return Err(ScheduleError::WrongState)}
        let d=self.ctx.raw_device();
        unsafe{for s in self.slots.drain(..){d.destroy_fence(s.fence,None)}
            d.destroy_command_pool(self.pool,None)}
        self.poisoned=true;Ok(())
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn saturation_and_consumer(){
        let mut b=Book::new(2).unwrap();
        let a=b.reserve().unwrap().unwrap();let c=b.reserve().unwrap().unwrap();
        assert!(b.reserve().unwrap().is_none());
        b.transition(a,State::Recording,State::Submitted).unwrap();
        assert_eq!(b.transition(a,State::Ready,State::Free),Err(ScheduleError::WrongState));
        b.transition(a,State::Submitted,State::Ready).unwrap();
        assert!(b.reserve().unwrap().is_none()); // GPU done is not consumer done.
        b.transition(a,State::Ready,State::Free).unwrap();
        let next=b.reserve().unwrap().unwrap();
        assert_eq!(next.index(),a.index());assert_ne!(next,a);
        assert_eq!(b.state(a),Err(ScheduleError::Stale));
        let foreign=Book::new(1).unwrap();
        assert_eq!(foreign.state(c),Err(ScheduleError::Stale));
        b.transition(c,State::Recording,State::Free).unwrap();
        b.transition(next,State::Recording,State::Free).unwrap();
        assert!(b.idle());
    }
    #[test]fn bounds(){
        assert!(Book::new(0).is_err());assert!(Book::new(5).is_err());
    }
}
