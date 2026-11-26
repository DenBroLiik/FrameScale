//! Standalone diagnostic: one allocation, two binary semaphores, 16 roundtrips.
//! Never call from the game renderer; CPU readbacks/fence waits verify content.
use crate::{Extent,gl_interop::{GlContext,ImportedImage},shared::{SharedImage,ImageKind,Handles},vulkan::DeviceContext};
use ash::vk;
const ROUNDS:u32=16;
const SIZE:u32=8;
const BYTES:usize=(SIZE*SIZE*4) as usize;
type Result<T>=std::result::Result<T,String>;
fn err(e:impl std::fmt::Debug)->String{format!("{e:?}")}
fn vkerr(e:vk::Result)->String{format!("Vulkan error {}",e.as_raw())}
fn color(round:u32)->[u8;4]{[(round*9+1) as u8,(round*3+2) as u8,(255-round*7) as u8,255]}
fn reply(round:u32)->[u8;4]{[(11+round*7) as u8,(239-round*5) as u8,(33+round*3) as u8,255]}
fn verify(bytes:&[u8],expected:[u8;4])->bool{bytes.len()==BYTES&&bytes.chunks_exact(4).all(|v|v==expected)}

// Diagnostic allocations are explicitly destroyed only after successful retirement.
// On error the caller must terminate the standalone process without device teardown.
struct Commands<'a>{
    ctx:&'a DeviceContext,pool:vk::CommandPool,cmd:vk::CommandBuffer,fence:vk::Fence,
    buffer:vk::Buffer,memory:vk::DeviceMemory,coherent:bool,
}
impl<'a> Commands<'a>{
    fn new(ctx:&'a DeviceContext)->Result<Self>{
        let d=ctx.raw_device();
        let pi=vk::CommandPoolCreateInfo::default().queue_family_index(ctx.family()).flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let pool=unsafe{d.create_command_pool(&pi,None)}.map_err(vkerr)?;
        let ai=vk::CommandBufferAllocateInfo::default().command_pool(pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1);
        let cmd=unsafe{d.allocate_command_buffers(&ai)}.map_err(vkerr)?[0];
        let fence=unsafe{d.create_fence(&vk::FenceCreateInfo::default(),None)}.map_err(vkerr)?;
        let bi=vk::BufferCreateInfo::default().size(BYTES as u64).usage(vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer=unsafe{d.create_buffer(&bi,None)}.map_err(vkerr)?;
        let req=unsafe{d.get_buffer_memory_requirements(buffer)};
        let props=unsafe{ctx.raw_instance().get_physical_device_memory_properties(ctx.physical())};
        let types=&props.memory_types[..props.memory_type_count as usize];
        let mut candidates=types.iter().enumerate().filter(|(i,m)| req.memory_type_bits&(1u32<<*i)!=0&&m.property_flags.contains(vk::MemoryPropertyFlags::HOST_VISIBLE));
        let (index,mt)=candidates.clone().find(|(_,m)|m.property_flags.contains(vk::MemoryPropertyFlags::HOST_COHERENT))
            .or_else(||candidates.next()).ok_or("no host-visible verification memory")?;
        let coherent=mt.property_flags.contains(vk::MemoryPropertyFlags::HOST_COHERENT);
        let mi=vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(index as u32);
        let memory=unsafe{d.allocate_memory(&mi,None)}.map_err(vkerr)?;
        unsafe{d.bind_buffer_memory(buffer,memory,0)}.map_err(vkerr)?;
        Ok(Self{ctx,pool,cmd,fence,buffer,memory,coherent})
    }
    fn begin(&self)->Result<()>{
        let d=self.ctx.raw_device();
        unsafe{
            d.reset_fences(&[self.fence]).map_err(vkerr)?;
            d.reset_command_buffer(self.cmd,vk::CommandBufferResetFlags::empty()).map_err(vkerr)?;
            d.begin_command_buffer(self.cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).map_err(vkerr)?;
        }
        Ok(())
    }
    fn range()->vk::ImageSubresourceRange{
        vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)
    }
    fn image_barrier(&self,h:Handles,old:vk::ImageLayout,src:vk::AccessFlags,dst:vk::AccessFlags,from:u32,to:u32,
        start:vk::PipelineStageFlags,end:vk::PipelineStageFlags){
        let b=vk::ImageMemoryBarrier::default().image(h.image).subresource_range(Self::range())
            .old_layout(old).new_layout(vk::ImageLayout::GENERAL).src_access_mask(src).dst_access_mask(dst)
            .src_queue_family_index(from).dst_queue_family_index(to);
        unsafe{self.ctx.raw_device().cmd_pipeline_barrier(self.cmd,start,end,vk::DependencyFlags::empty(),&[],&[],&[b])}
    }
    fn initialize(&self,h:Handles){
        self.image_barrier(h,vk::ImageLayout::UNDEFINED,vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_WRITE,
            vk::QUEUE_FAMILY_IGNORED,vk::QUEUE_FAMILY_IGNORED,vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
    }
    fn clear(&self,h:Handles,c:[u8;4]){
        let value=vk::ClearColorValue{float32:c.map(|v|f32::from(v)/255.)};
        unsafe{self.ctx.raw_device().cmd_clear_color_image(self.cmd,h.image,vk::ImageLayout::GENERAL,&value,&[Self::range()])}
    }
    fn release(&self,h:Handles){
        self.image_barrier(h,vk::ImageLayout::GENERAL,vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::empty(),
            self.ctx.family(),vk::QUEUE_FAMILY_EXTERNAL,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::BOTTOM_OF_PIPE);
    }
    fn acquire(&self,h:Handles){
        self.image_barrier(h,vk::ImageLayout::GENERAL,vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_READ,
            vk::QUEUE_FAMILY_EXTERNAL,self.ctx.family(),vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
    }
    fn copy(&self,h:Handles){
        let region=vk::BufferImageCopy::default().image_subresource(vk::ImageSubresourceLayers::default()
            .aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1)).image_extent(vk::Extent3D{width:SIZE,height:SIZE,depth:1});
        let b=vk::BufferMemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::HOST_READ)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.buffer).size(vk::WHOLE_SIZE);
        unsafe{
            let d=self.ctx.raw_device();
            d.cmd_copy_image_to_buffer(self.cmd,h.image,vk::ImageLayout::GENERAL,self.buffer,&[region]);
            d.cmd_pipeline_barrier(self.cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&[],&[b],&[]);
        }
    }
    fn read_to_write(&self,h:Handles){
        self.image_barrier(h,vk::ImageLayout::GENERAL,vk::AccessFlags::TRANSFER_READ,vk::AccessFlags::TRANSFER_WRITE,
            vk::QUEUE_FAMILY_IGNORED,vk::QUEUE_FAMILY_IGNORED,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::TRANSFER);
    }
    fn submit(&self,wait:Option<vk::Semaphore>,signal:Option<vk::Semaphore>)->Result<()>{
        let d=self.ctx.raw_device();
        let cmd=[self.cmd];let waits=wait.into_iter().collect::<Vec<_>>();
        let signals=signal.into_iter().collect::<Vec<_>>();
        let stages=vec![vk::PipelineStageFlags::ALL_COMMANDS;waits.len()];
        let submit=vk::SubmitInfo::default().command_buffers(&cmd).wait_semaphores(&waits)
            .wait_dst_stage_mask(&stages).signal_semaphores(&signals);
        unsafe{
            d.end_command_buffer(self.cmd).map_err(vkerr)?;
            d.queue_submit(self.ctx.queue(),&[submit],self.fence).map_err(vkerr)?;
            d.wait_for_fences(&[self.fence],true,5_000_000_000).map_err(vkerr)?;
        }
        Ok(())
    }
    fn verify(&self,expected:[u8;4])->Result<()>{
        let d=self.ctx.raw_device();
        let ptr=unsafe{d.map_memory(self.memory,0,vk::WHOLE_SIZE,vk::MemoryMapFlags::empty())}.map_err(vkerr)?;
        let result=(||{
            if !self.coherent{
                let range=vk::MappedMemoryRange::default().memory(self.memory).size(vk::WHOLE_SIZE);
                unsafe{d.invalidate_mapped_memory_ranges(&[range])}.map_err(vkerr)?;
            }
            let data=unsafe{std::slice::from_raw_parts(ptr.cast::<u8>(),BYTES)};
            if !verify(data,expected){return Err("GL to Vulkan pixel mismatch".into())}
            Ok(())
        })();
        unsafe{d.unmap_memory(self.memory)};
        result
    }
    fn close(self){
        let d=self.ctx.raw_device();
        unsafe{
            d.destroy_fence(self.fence,None);d.destroy_command_pool(self.pool,None);
            d.destroy_buffer(self.buffer,None);d.free_memory(self.memory,None);
        }
    }
}
/// # Safety
/// Dedicated standalone process; current fresh EGL context, matched device,
/// no other work. On ANY error terminate the process without normal GL/VK
/// destruction; a semaphore may be pending. No timeout guarantee for GL calls.
pub unsafe fn run(gl:&GlContext,ctx:&DeviceContext)->Result<()>{
    let image=SharedImage::external(ctx,Extent{w:SIZE,h:SIZE},ImageKind::Rgba8).map_err(err)?;
    let mut pair=ImportedImage::new(gl,image).map_err(err)?;
    let (_,h)=pair.handles();
    let cmds=Commands::new(ctx)?;
    cmds.begin()?;cmds.initialize(h);cmds.clear(h,color(0));cmds.release(h);
    cmds.submit(None,Some(h.to_gl))?;
    for round in 0..ROUNDS{
        unsafe{pair.wait_gl()}.map_err(err)?;
        unsafe{pair.verify_and_write_rgba8(color(round),reply(round))}.map_err(err)?;
        unsafe{pair.signal_vk()}.map_err(err)?;
        cmds.begin()?;cmds.acquire(h);cmds.copy(h);
        let more=round+1<ROUNDS;
        if more{cmds.read_to_write(h);cmds.clear(h,color(round+1));cmds.release(h)}
        cmds.submit(Some(h.to_vk),more.then_some(h.to_gl))?;
        cmds.verify(reply(round))?;
    }
    // Last submission consumes the GL signal, retaining Vulkan ownership.
    cmds.close();unsafe{pair.close()}.map_err(err)?;
    println!("PASS: {ROUNDS} bidirectional RGBA8 cycles; {} pixels per direction",ROUNDS*SIZE*SIZE);
    Ok(())
}
/// Local Vulkan test exercises clear, transfer, host visibility and verification
/// without using external handles or claiming GL interop.
pub fn local_smoke(ctx:&DeviceContext)->Result<()>{
    let mut im=SharedImage::local(ctx,Extent{w:SIZE,h:SIZE},ImageKind::Rgba8).map_err(err)?;
    let h=im.handles();let cmds=Commands::new(ctx)?;
    cmds.begin()?;cmds.initialize(h);cmds.clear(h,color(0));
    let b=vk::ImageMemoryBarrier::default().image(h.image).subresource_range(Commands::range())
        .old_layout(vk::ImageLayout::GENERAL).new_layout(vk::ImageLayout::GENERAL)
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::TRANSFER_READ)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED);
    unsafe{ctx.raw_device().cmd_pipeline_barrier(cmds.cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),&[],&[],&[b])};
    cmds.copy(h);cmds.submit(None,None)?;cmds.verify(color(0))?;
    cmds.close();unsafe{im.close()};println!("PASS: local Vulkan clear and pixel readback (no GL interop)");Ok(())
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn patterns_and_validation(){
        for i in 0..ROUNDS{assert_ne!(color(i),reply(i));if i>0{assert_ne!(color(i),color(i-1));assert_ne!(reply(i),reply(i-1));}}
        let expected=color(0);let mut pixels=expected.repeat(SIZE as usize*SIZE as usize);
        assert!(verify(&pixels,expected));pixels[BYTES-1]^=1;assert!(!verify(&pixels,expected));assert!(!verify(&pixels[..4],expected));
    }
}
