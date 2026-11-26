//! SDK-independent control plane. GPU ownership and submission are caller contracts.
use std::{marker::PhantomData, rc::Rc};

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Extent { pub w: u32, pub h: u32 }
impl Extent {
    pub fn valid(self) -> bool {
        self.w > 0 && self.h > 0 && self.w <= 8192 && self.h <= 8192
            && u64::from(self.w)*u64::from(self.h) <= 16_777_216
    }
    fn fits(self, b: Self) -> bool { self.w <= b.w && self.h <= b.h }
}
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Optimal { pub recommended: Extent, pub minimum: Extent, pub maximum: Extent, pub output: Extent }
impl Optimal {
    pub fn valid(self) -> bool {
        [self.recommended,self.minimum,self.maximum,self.output].iter().all(|e| e.valid())
            && self.minimum.fits(self.recommended) && self.recommended.fits(self.maximum)
            && self.maximum.fits(self.output)
    }
}
#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quality { Performance=0, Balanced=1, Quality=2, UltraPerformance=3 }
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Frame { pub jitter_x:f32, pub jitter_y:f32, pub pre_exposure:f32, pub frame_ms:f32, pub reset:u32 }
impl Frame {
    pub fn valid(self)->bool {
        self.jitter_x.is_finite() && self.jitter_x.abs()<=1.0 && self.jitter_y.is_finite()
            && self.jitter_y.abs()<=1.0 && self.pre_exposure.is_finite() && self.pre_exposure>0.0
            && self.frame_ms.is_finite() && self.frame_ms>0.0 && self.frame_ms<=1000.0 && self.reset<=1
    }
}
#[repr(u32)]
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Format { Hdr=1, Depth=2, Motion=3 }
#[repr(C)]
#[derive(Clone,Copy,Debug)]
pub struct Image {
    pub image:u64, pub view:u64, pub extent:Extent, pub format:Format,
    pub writable:u32,
}
#[repr(C)]
#[derive(Clone,Copy,Debug)]
pub struct Images { pub color:Image, pub depth:Image, pub motion:Image, pub output:Image }
impl Images {
    pub fn valid(self, size:Optimal)->bool {
        let items=[self.color,self.depth,self.motion,self.output];
        let formats=[Format::Hdr,Format::Depth,Format::Motion,Format::Hdr];
        for (i,img) in items.iter().enumerate() {
            if img.image==0 || img.view==0 || img.format!=formats[i]
                || img.extent!=if i==3 {size.output}else{size.recommended}
                || img.writable!=u32::from(i==3) {return false}
            if items[..i].iter().any(|other| other.image==img.image||other.view==img.view){return false}
        }
        true
    }
}
pub type Uuid=[u8;16];
#[derive(Clone,Copy,Debug)]
pub struct DeviceIdentity {pub device:Uuid,pub driver:Uuid,pub vendor:u32}
#[derive(Debug,PartialEq,Eq)]
pub enum Error { Invalid(&'static str), State, NoDevice, AmbiguousDevice, Native(u32) }
pub fn select_device(gl:&[Uuid],driver:Uuid,vk:&[DeviceIdentity])->Result<usize,Error>{
    if gl.is_empty()||gl.len()>16||driver==[0;16]||gl.contains(&[0;16])||vk.len()>256 {
        return Err(Error::Invalid("UUID inventory"));
    }
    let mut found=None;
    for (i,v) in vk.iter().enumerate() {
        if v.vendor==0x10de&&v.driver==driver&&gl.contains(&v.device) {
            if found.is_some(){return Err(Error::AmbiguousDevice)}
            found=Some(i);
        }
    }
    found.ok_or(Error::NoDevice)
}
/// Stable deduplication of SDK and interop extension requirements. No implicit support.
pub fn required_extensions<'a>(sdk:&[&'a str], interop:&[&'a str], supported:&[&str])->Result<Vec<&'a str>,Error>{
    let mut result=Vec::new();
    for &name in sdk.iter().chain(interop) {
        if name.is_empty()||!supported.contains(&name){return Err(Error::Invalid("required Vulkan extension unavailable"))}
        if !result.contains(&name){result.push(name)}
    }
    Ok(result)
}

/// # Safety
/// Implementation must honor Vulkan/NGX ABI, retain any native resources until
/// explicit close, and never assume a successful record has completed GPU work.
pub unsafe trait Driver {
    fn optimal(&mut self, output:Extent, quality:Quality)->Result<Optimal,Error>;
    unsafe fn create(&mut self,cmd:u64,size:Optimal,quality:Quality)->Result<(),Error>;
    unsafe fn evaluate(&mut self,cmd:u64,images:Images,frame:Frame)->Result<(),Error>;
    unsafe fn close(&mut self)->Result<(),Error>;
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
enum State { Ready, RecordingInit, Active, RecordedFrame, Failed, Closed }

/// Explicit command-buffer lifecycle. Neither Send nor Sync.
/// Never implicitly destroys in-flight GPU work on Drop.
#[must_use = "a live session must be explicitly closed after GPU retirement"]
pub struct Session<D:Driver> {
    driver:D, state:State, size:Optimal, first:bool,
    _thread:PhantomData<Rc<()>>,
}
impl<D:Driver> Session<D> {
    pub fn new(driver:D)->Self{
        Self{driver,state:State::Ready,size:Optimal::default(),first:true,_thread:PhantomData}
    }
    pub fn optimal(&mut self,output:Extent,q:Quality)->Result<Optimal,Error>{
        if self.state!=State::Ready{return Err(Error::State)}
        if !output.valid(){return Err(Error::Invalid("output extent"))}
        let s=self.driver.optimal(output,q)?;
        if !s.valid()||s.output!=output{return Err(Error::Invalid("SDK extent"))}
        Ok(s)
    }
    /// # Safety
    /// cmd is recording outside a render pass, belongs to the selected device.
    pub unsafe fn create(&mut self,cmd:u64,size:Optimal,q:Quality)->Result<(),Error>{
        if cmd==0||!size.valid(){return Err(Error::Invalid("create"))}
        let actual=self.optimal(size.output,q)?;
        if actual.recommended!=size.recommended{return Err(Error::Invalid("SDK recommendation changed"))}
        self.state=State::Failed;
        unsafe{self.driver.create(cmd,actual,q)?};
        self.size=actual;self.state=State::RecordingInit;Ok(())
    }
    /// # Safety
    /// Initialization was submitted successfully and all its GPU work completed.
    pub unsafe fn initialization_retired(&mut self)->Result<(),Error>{
        if self.state!=State::RecordingInit{return Err(Error::State)}
        self.state=State::Active;Ok(())
    }
    /// # Safety
    /// Valid native handles, correct layouts, usage flags, non-overlapping memory,
    /// required barriers, device ownership and lifetimes. No concurrent use.
    pub unsafe fn evaluate(&mut self,cmd:u64,images:Images,mut frame:Frame)->Result<(),Error>{
        if self.state!=State::Active{return Err(Error::State)}
        if cmd==0||!frame.valid()||!images.valid(self.size){return Err(Error::Invalid("frame"))}
        frame.reset=u32::from(self.first||frame.reset!=0);
        self.state=State::Failed;
        unsafe{self.driver.evaluate(cmd,images,frame)?};
        self.state=State::RecordedFrame;Ok(())
    }
    /// # Safety
    /// Recorded evaluation was submitted and all GPU work completed.
    pub unsafe fn frame_retired(&mut self)->Result<(),Error>{
        if self.state!=State::RecordedFrame{return Err(Error::State)}
        self.first=false;self.state=State::Active;Ok(())
    }
    /// Discarded frame invalidates temporal state. Session cannot record again.
    pub fn invalidate(&mut self){if self.state!=State::Closed{self.state=State::Failed}}
    /// # Safety
    /// All command buffers that reference NGX have completed or been discarded.
    pub unsafe fn close(&mut self)->Result<(),Error>{
        if self.state==State::Closed{return Ok(())}
        self.state=State::Failed;
        unsafe{self.driver.close()?};
        self.state=State::Closed;Ok(())
    }
}
#[cfg(feature="ngx")]
pub mod ngx;

#[cfg(test)]
mod tests {
    use super::*;
    fn size()->Optimal {Optimal{recommended:Extent{w:1280,h:800},minimum:Extent{w:960,h:600},maximum:Extent{w:1536,h:960},output:Extent{w:1920,h:1200}}}
    fn images()->Images {
        let mk=|id,format,extent,writable|Image{image:id,view:id+10,extent,format,writable};
        let s=size();
        Images{color:mk(1,Format::Hdr,s.recommended,0),depth:mk(2,Format::Depth,s.recommended,0),motion:mk(3,Format::Motion,s.recommended,0),output:mk(4,Format::Hdr,s.output,1)}
    }
    fn frame()->Frame {Frame{jitter_x:0.25,jitter_y:-0.25,pre_exposure:1.,frame_ms:12.5,reset:0}}
    #[derive(Default)]
    struct Mock {resets:Vec<u32>,fail:bool,closed:u32}
    unsafe impl Driver for Mock {
        fn optimal(&mut self,_:Extent,_:Quality)->Result<Optimal,Error>{Ok(size())}
        unsafe fn create(&mut self,_:u64,_:Optimal,_:Quality)->Result<(),Error>{Ok(())}
        unsafe fn evaluate(&mut self,_:u64,_:Images,f:Frame)->Result<(),Error>{
            self.resets.push(f.reset);if self.fail{Err(Error::Native(7))}else{Ok(())}
        }
        unsafe fn close(&mut self)->Result<(),Error>{self.closed+=1;Ok(())}
    }
    #[test] fn lifecycle(){
        let mut s=Session::new(Mock::default());
        unsafe{
            assert_eq!(s.evaluate(1,images(),frame()),Err(Error::State));
            s.create(1,size(),Quality::Quality).unwrap();
            assert_eq!(s.evaluate(1,images(),frame()),Err(Error::State));
            s.initialization_retired().unwrap();
            s.evaluate(1,images(),frame()).unwrap();
            assert_eq!(s.evaluate(1,images(),frame()),Err(Error::State));
            s.frame_retired().unwrap();s.evaluate(1,images(),frame()).unwrap();s.frame_retired().unwrap();
            assert_eq!(s.driver.resets,vec![1,0]);
            s.close().unwrap();s.close().unwrap();
            assert_eq!(s.driver.closed,1);
        }
    }
    #[test] fn failure_and_validation(){
        let mut s=Session::new(Mock{fail:true,..Default::default()});
        unsafe{
            s.create(1,size(),Quality::Quality).unwrap();s.initialization_retired().unwrap();
            let mut i=images();i.output.image=i.color.image;
            assert!(matches!(s.evaluate(1,i,frame()),Err(Error::Invalid(_))));
            let mut f=frame();f.jitter_x=f32::NAN;
            assert!(matches!(s.evaluate(1,images(),f),Err(Error::Invalid(_))));
            assert_eq!(s.evaluate(1,images(),frame()),Err(Error::Native(7)));
            assert_eq!(s.evaluate(1,images(),frame()),Err(Error::State));s.close().unwrap();
        }
        assert!(!Extent{w:8192,h:8192}.valid());
    }
    #[test] fn match_device_and_extensions(){
        let a=DeviceIdentity{device:[1;16],driver:[2;16],vendor:0x10de};
        let intel=DeviceIdentity{vendor:0x8086,..a};
        assert_eq!(select_device(&[[1;16]],[2;16],&[intel,a]),Ok(1));
        assert_eq!(select_device(&[[1;16]],[2;16],&[a,a]),Err(Error::AmbiguousDevice));
        assert_eq!(select_device(&[[1;16]],[3;16],&[a]),Err(Error::NoDevice));
        assert_eq!(required_extensions(&["a","b"],&["b","c"],&["a","b","c"]),Ok(vec!["a","b","c"]));
        assert!(required_extensions(&["a"],&["b"],&["a"]).is_err());
    }
}
