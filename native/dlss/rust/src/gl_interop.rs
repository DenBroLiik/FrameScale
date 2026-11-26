//! OpenGL 4.5 external-object import. EGL diagnostic context only.
//! All unsafe GL calls remain on the owner thread and current context.
use crate::{Uuid,shared::{SharedImage,Handles,ImageKind}};
use std::{ffi::{CStr,c_char,c_void},marker::PhantomData,rc::Rc,os::fd::{OwnedFd,AsRawFd,IntoRawFd}};
use std::ptr::null;
type U=u32; type I=i32; type P=*const c_void;
type Result<T>=std::result::Result<T,GlError>;
#[derive(Debug)]
pub enum GlError { Unsupported(&'static str), Gl(&'static str,U), WrongContext, Identity, Vulkan(crate::vulkan::VkError) }
impl From<crate::vulkan::VkError> for GlError{fn from(e:crate::vulkan::VkError)->Self{Self::Vulkan(e)}}
#[link(name="EGL")]
unsafe extern "C" {
    fn eglGetCurrentContext()->P;
    fn eglGetProcAddress(name:*const c_char)->P;
}
const OPAQUE_FD:U=0x9586;
const GENERAL:U=0x958d;
const DEDICATED:U=0x9581;
const TEXTURE_2D:U=0x0de1;
const TILING:U=0x9580;
const OPTIMAL: I=0x9584;
const UUID_COUNT:U=0x9596;
const DEVICE_UUID:U=0x9597;
const DRIVER_UUID:U=0x9598;
fn gl_format(k:ImageKind)->U{match k{
    ImageKind::Rgba8=>0x8058,ImageKind::HdrInput|ImageKind::HdrOutput=>0x881a,
    ImageKind::DepthCopy=>0x822e,ImageKind::Motion=>0x822f,
}}
macro_rules! functions{
    ($($field:ident:$ty:ty=$name:literal),+ $(,)?)=>{
        struct Api{$($field:$ty,)+}
        impl Api{
            fn load()->Result<Self>{
                Ok(Self{$($field:{
                    let p=unsafe{eglGetProcAddress(concat!($name,"\0").as_ptr().cast())};
                    if p.is_null(){return Err(GlError::Unsupported($name))}
                    // Each concrete signature matches the Khronos OpenGL declaration.
                    unsafe{std::mem::transmute::<P,$ty>(p)}
                },)+})
            }
        }
    }
}
functions!{
    error:unsafe extern "system" fn()->U="glGetError",
    integer:unsafe extern "system" fn(U,*mut I)="glGetIntegerv",
    string_i:unsafe extern "system" fn(U,U)->*const u8="glGetStringi",
    uuid_i:unsafe extern "system" fn(U,U,*mut u8)="glGetUnsignedBytei_vEXT",
    uuid:unsafe extern "system" fn(U,*mut u8)="glGetUnsignedBytevEXT",
    create_mem:unsafe extern "system" fn(I,*mut U)="glCreateMemoryObjectsEXT",
    delete_mem:unsafe extern "system" fn(I,*const U)="glDeleteMemoryObjectsEXT",
    mem_param:unsafe extern "system" fn(U,U,*const I)="glMemoryObjectParameterivEXT",
    import_mem:unsafe extern "system" fn(U,u64,U,I)="glImportMemoryFdEXT",
    create_tex:unsafe extern "system" fn(U,I,*mut U)="glCreateTextures",
    delete_tex:unsafe extern "system" fn(I,*const U)="glDeleteTextures",
    tex_param:unsafe extern "system" fn(U,U,I)="glTextureParameteri",
    storage:unsafe extern "system" fn(U,I,U,I,I,U,u64)="glTextureStorageMem2DEXT",
    gen_sem:unsafe extern "system" fn(I,*mut U)="glGenSemaphoresEXT",
    delete_sem:unsafe extern "system" fn(I,*const U)="glDeleteSemaphoresEXT",
    import_sem:unsafe extern "system" fn(U,U,I)="glImportSemaphoreFdEXT",
    wait:unsafe extern "system" fn(U,U,*const U,U,*const U,*const U)="glWaitSemaphoreEXT",
    signal:unsafe extern "system" fn(U,U,*const U,U,*const U,*const U)="glSignalSemaphoreEXT",
    flush:unsafe extern "system" fn()="glFlush",
    read_texture:unsafe extern "system" fn(U,I,U,U,I,*mut c_void)="glGetTextureImage",
    clear_texture:unsafe extern "system" fn(U,I,U,U,P)="glClearTexImage",
}
fn transfer(fd:OwnedFd,import:impl FnOnce(I)->Result<()>)->Result<()>{
    // Khronos EXT_external_objects_fd transfers ownership on successful import only.
    import(fd.as_raw_fd())?;
    let _=fd.into_raw_fd();
    Ok(())
}
pub struct GlContext{
    api:Api,context:P,devices:Vec<Uuid>,driver:Uuid,max_texture:I,_thread:PhantomData<Rc<()>>,
}
impl GlContext{
    /// # Safety
    /// The current EGL OpenGL context is caller-owned and remains alive until
    /// all imports are explicitly released. No concurrent context use.
    pub unsafe fn capture()->Result<Self>{
        let context=unsafe{eglGetCurrentContext()};
        if context.is_null(){return Err(GlError::WrongContext)}
        let api=Api::load()?;
        let mut g=Self{api,context,devices:vec![],driver:[0;16],max_texture:0,_thread:PhantomData};
        g.check("pre-existing error")?;
        let version=(g.integer(0x821b)?,g.integer(0x821c)?);
        if version<(4,5){return Err(GlError::Unsupported("OpenGL 4.5"))}
        let count=g.integer(0x821d)?;
        if !(0..=65536).contains(&count){return Err(GlError::Unsupported("extension count"))}
        let required=[c"GL_EXT_memory_object",c"GL_EXT_memory_object_fd",c"GL_EXT_semaphore",c"GL_EXT_semaphore_fd"];
        let mut found=[false;4];
        for i in 0..count{
            let ptr=unsafe{(g.api.string_i)(0x1f03,i as U)};
            if ptr.is_null(){return Err(GlError::Unsupported("GL extension string"))}
            let value=unsafe{CStr::from_ptr(ptr.cast())};
            for (i,name) in required.iter().enumerate(){found[i]|=value==*name;}
        }
        g.check("extension query")?;
        if !found.iter().all(|&v|v){return Err(GlError::Unsupported("external memory/semaphore FD extensions"))}
        let n=g.integer(UUID_COUNT)?;
        if !(1..=16).contains(&n){return Err(GlError::Identity)}
        for i in 0..n{
            let mut uuid=[0;16];unsafe{(g.api.uuid_i)(DEVICE_UUID,i as U,uuid.as_mut_ptr())};
            g.devices.push(uuid);
        }
        unsafe{(g.api.uuid)(DRIVER_UUID,g.driver.as_mut_ptr())};
        g.check("UUID query")?;
        if g.driver==[0;16]||g.devices.contains(&[0;16]){return Err(GlError::Identity)}
        g.max_texture=g.integer(0x0d33)?;
        if g.max_texture<=0{return Err(GlError::Unsupported("texture limit"))}
        Ok(g)
    }
    pub fn devices(&self)->&[Uuid]{&self.devices}
    pub fn driver(&self)->Uuid{self.driver}
    fn current(&self)->Result<()>{
        if unsafe{eglGetCurrentContext()}!=self.context{return Err(GlError::WrongContext)}
        Ok(())
    }
    fn check(&self,stage:&'static str)->Result<()>{
        self.current()?;
        let e=unsafe{(self.api.error)()};
        if e==0{Ok(())}else{Err(GlError::Gl(stage,e))}
    }
    fn integer(&self,name:U)->Result<I>{
        self.current()?;let mut i=0;unsafe{(self.api.integer)(name,&mut i)};self.check("integer query")?;Ok(i)
    }
}
struct Objects<'g>{gl:&'g GlContext,mem:U,tex:U,to_gl:U,to_vk:U}
impl Drop for Objects<'_>{
    fn drop(&mut self){
        if self.gl.current().is_err(){return}
        let a=&self.gl.api;
        unsafe{
            if self.tex!=0{(a.delete_tex)(1,&self.tex)}
            if self.to_vk!=0{(a.delete_sem)(1,&self.to_vk)}
            if self.to_gl!=0{(a.delete_sem)(1,&self.to_gl)}
            if self.mem!=0{(a.delete_mem)(1,&self.mem)}
        }
    }
}
/// Paired Vulkan allocation and GL import, bound to the original context.
/// Explicit close after all work retires; dropping an exposed pair leaks it.
#[must_use]
pub struct ImportedImage<'g,'v>{
    objects:Option<Objects<'g>>,image:Option<SharedImage<'v>>,exposed:bool,
}
impl<'g,'v> ImportedImage<'g,'v>{
    pub fn new(gl:&'g GlContext,mut image:SharedImage<'v>)->Result<Self>{
        if !image.pristine_external(){return Err(GlError::Unsupported("expected fresh external allocation"))}
        gl.check("pre-import error")?;
        let id=image.identity();
        if id.vendor!=0x10de||id.driver!=gl.driver||!gl.devices.contains(&id.device){return Err(GlError::Identity)}
        // No GPU work or external access has occurred. On failure, GL rollback
        // precedes Vulkan destruction, even after a partial successful import.
        let res=Self::import(gl,&mut image);
        match res {
            Ok(objects)=>Ok(Self{objects:Some(objects),image:Some(image),exposed:false}),
            Err(e)=>{unsafe{image.close()};Err(e)}
        }
    }
    fn import(gl:&'g GlContext,image:&mut SharedImage<'v>)->Result<Objects<'g>>{
        let fd=image.export()?;
        if fd.extent.w>gl.max_texture as u32||fd.extent.h>gl.max_texture as u32||fd.allocation_size==0{
            return Err(GlError::Unsupported("import extent or allocation size"))
        }
        let a=&gl.api;
        let mut o=Objects{gl,mem:0,tex:0,to_gl:0,to_vk:0};
        unsafe{(a.create_mem)(1,&mut o.mem)};
        gl.check("create memory")?;
        if o.mem==0{return Err(GlError::Unsupported("zero memory object"))}
        unsafe{(a.mem_param)(o.mem,DEDICATED,&1)};
        gl.check("dedicated memory flag")?;
        transfer(fd.memory,|raw|{unsafe{(a.import_mem)(o.mem,fd.allocation_size,OPAQUE_FD,raw)};gl.check("memory FD import")})?;
        unsafe{(a.create_tex)(TEXTURE_2D,1,&mut o.tex)};
        gl.check("create texture")?;
        if o.tex==0{return Err(GlError::Unsupported("zero texture"))}
        unsafe{(a.tex_param)(o.tex,TILING,OPTIMAL)};
        gl.check("texture tiling")?;
        unsafe{(a.storage)(o.tex,1,gl_format(fd.kind),fd.extent.w as I,fd.extent.h as I,o.mem,0)};
        gl.check("texture storage")?;
        unsafe{(a.gen_sem)(1,&mut o.to_gl)};
        gl.check("create to-GL semaphore")?;
        if o.to_gl==0{return Err(GlError::Unsupported("zero to-GL semaphore"))}
        transfer(fd.to_gl,|raw|{unsafe{(a.import_sem)(o.to_gl,OPAQUE_FD,raw)};gl.check("to-GL FD import")})?;
        unsafe{(a.gen_sem)(1,&mut o.to_vk)};
        gl.check("create to-VK semaphore")?;
        if o.to_vk==0{return Err(GlError::Unsupported("zero to-VK semaphore"))}
        transfer(fd.to_vk,|raw|{unsafe{(a.import_sem)(o.to_vk,OPAQUE_FD,raw)};gl.check("to-VK FD import")})?;
        Ok(o)
    }
    pub fn handles(&mut self)->(U,Handles){
        self.exposed=true;
        (self.objects.as_ref().unwrap().tex,self.image.as_mut().unwrap().handles())
    }
    /// # Safety
    /// Vulkan release to EXTERNAL in GENERAL and to_gl signal must have been submitted;
    /// each binary semaphore is consumed exactly once. No competing access.
    pub unsafe fn wait_gl(&mut self)->Result<()>{
        self.exposed=true;
        let o=self.objects.as_ref().unwrap();o.gl.check("pre-wait")?;
        unsafe{(o.gl.api.wait)(o.to_gl,0,null(),1,&o.tex,&GENERAL)};
        o.gl.check("wait semaphore")
    }
    /// # Safety
    /// GL owns this image; all GL work is ordered before signal, and Vulkan
    /// waits on to_vk then acquires from EXTERNAL in GENERAL.
    pub unsafe fn signal_vk(&mut self)->Result<()>{
        self.exposed=true;
        let o=self.objects.as_ref().unwrap();o.gl.check("pre-signal")?;
        unsafe{(o.gl.api.signal)(o.to_vk,0,null(),1,&o.tex,&GENERAL);(o.gl.api.flush)()};
        o.gl.check("signal semaphore")
    }
    /// Diagnostic only, not a real-time frame path.
    /// # Safety
    /// Fresh private RGBA8 8x8 texture, GL owns it after wait_gl, default pixel
    /// pack/unpack state and no pixel buffer bindings. No concurrent access.
    pub(crate) unsafe fn verify_and_write_rgba8(&mut self,expected:[u8;4],next:[u8;4])->Result<()>{
        let o=self.objects.as_ref().unwrap();
        o.gl.check("pre-readback")?;
        if o.gl.integer(0x88ed)?!=0||o.gl.integer(0x88ef)?!=0{
            return Err(GlError::Unsupported("pixel buffer bound"))
        }
        let mut bytes=[0u8;256];
        unsafe{(o.gl.api.read_texture)(o.tex,0,0x1908,0x1401,256,bytes.as_mut_ptr().cast())};
        o.gl.check("GL readback")?;
        if bytes.chunks_exact(4).any(|v|v!=expected){
            return Err(GlError::Unsupported("Vulkan to GL pixel mismatch"))
        }
        unsafe{(o.gl.api.clear_texture)(o.tex,0,0x1908,0x1401,next.as_ptr().cast())};
        o.gl.check("GL texture clear")
    }
    /// # Safety
    /// Both APIs have retired all work; no external handles or recorded commands
    /// are in use. The original GL context must be current.
    pub unsafe fn close(mut self)->Result<()>{
        self.objects.as_ref().unwrap().gl.current()?;
        drop(self.objects.take());
        if let Some(image)=self.image.take(){unsafe{image.close()}}
        Ok(())
    }
}
impl Drop for ImportedImage<'_,'_>{
    fn drop(&mut self){
        if self.exposed||self.objects.as_ref().is_some_and(|o|o.gl.current().is_err()){
            if let Some(o)=self.objects.take(){std::mem::forget(o)}
            if let Some(i)=self.image.take(){std::mem::forget(i)}
        }else{
            drop(self.objects.take());
            if let Some(i)=self.image.take(){unsafe{i.close()}}
        }
    }
}
#[cfg(test)]
mod tests{
    use super::*;
    use std::{fs::File,os::fd::FromRawFd};
    #[test]fn fd_transfer(){
        let fd:OwnedFd=File::open("/dev/null").unwrap().into();
        let mut received=None;
        transfer(fd,|n|{received=Some(n);Ok(())}).unwrap();
        // Simulate the successful importer taking and eventually closing ownership.
        drop(unsafe{OwnedFd::from_raw_fd(received.unwrap())});
        let fd:OwnedFd=File::open("/dev/null").unwrap().into();
        assert!(transfer(fd,|_|Err(GlError::Unsupported("test failure"))).is_err());
    }
    #[test]fn formats(){
        assert_eq!(gl_format(ImageKind::HdrInput),gl_format(ImageKind::HdrOutput));
        assert_eq!(gl_format(ImageKind::Motion),0x822f);
    }
}
