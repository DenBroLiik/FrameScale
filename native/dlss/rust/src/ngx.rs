//! Optional FFI. No SDK dependency when the feature is disabled.
use super::*;
use std::{ffi::{c_char,c_void,CString}, sync::atomic::{AtomicBool,Ordering}};
static OCCUPIED:AtomicBool=AtomicBool::new(false);
unsafe extern "C" {
    fn fs_ngx_open(instance:u64,gpu:u64,device:u64,project:*const c_char,data:*const i32,sdk:*const i32,out:*mut *mut c_void)->u32;
    fn fs_ngx_optimal(ctx:*mut c_void,output:Extent,quality:u32,out:*mut Optimal)->u32;
    fn fs_ngx_create(ctx:*mut c_void,cmd:u64,size:Optimal,quality:u32)->u32;
    fn fs_ngx_eval(ctx:*mut c_void,cmd:u64,images:*const Images,frame:Frame)->u32;
    fn fs_ngx_close(ctx:*mut c_void)->u32;
}
fn status(v:u32)->Result<(),Error>{if v==0{Ok(())}else{Err(Error::Native(v))}}
fn wide(s:&str)->Result<Vec<i32>,Error>{
    if !s.starts_with('/')||s.contains('\0'){return Err(Error::Invalid("absolute SDK/data path"))}
    Ok(s.chars().map(|c|c as i32).chain(std::iter::once(0)).collect())
}
pub struct Ngx {ctx:*mut c_void,_thread:PhantomData<Rc<()>>}
impl Ngx {
    /// # Safety
    /// Handles are live, same NVIDIA device, with all NGX-required features and
    /// extensions enabled. No other NGX session in this process. Paths trusted.
    /// Context and GPU must outlive the returned session, including explicit close.
    pub unsafe fn open(instance:u64,gpu:u64,device:u64,project:&str,data:&str,sdk:&str)->Result<Session<Self>,Error>{
        if instance==0||gpu==0||device==0||project.is_empty(){return Err(Error::Invalid("NGX context"))}
        let project=CString::new(project).map_err(|_|Error::Invalid("project ID"))?;
        let data=wide(data)?;let sdk=wide(sdk)?;
        if OCCUPIED.swap(true,Ordering::AcqRel){return Err(Error::State)}
        let mut ctx=std::ptr::null_mut();
        let r=unsafe{fs_ngx_open(instance,gpu,device,project.as_ptr(),data.as_ptr(),sdk.as_ptr(),&mut ctx)};
        if r!=0||ctx.is_null(){
            // If cleanup failed, shim returns a non-null quarantined context. Never reopen.
            if ctx.is_null(){OCCUPIED.store(false,Ordering::Release)}
            return Err(Error::Native(if r==0{u32::MAX}else{r}))
        }
        Ok(Session::new(Self{ctx,_thread:PhantomData}))
    }
}
unsafe impl Driver for Ngx {
    fn optimal(&mut self,output:Extent,quality:Quality)->Result<Optimal,Error>{
        let mut out=Optimal::default();
        status(unsafe{fs_ngx_optimal(self.ctx,output,quality as u32,&mut out)})?;Ok(out)
    }
    unsafe fn create(&mut self,cmd:u64,size:Optimal,q:Quality)->Result<(),Error>{status(unsafe{fs_ngx_create(self.ctx,cmd,size,q as u32)})}
    unsafe fn evaluate(&mut self,cmd:u64,images:Images,frame:Frame)->Result<(),Error>{status(unsafe{fs_ngx_eval(self.ctx,cmd,&images,frame)})}
    unsafe fn close(&mut self)->Result<(),Error>{
        if self.ctx.is_null(){return Ok(())}
        status(unsafe{fs_ngx_close(self.ctx)})?;
        self.ctx=std::ptr::null_mut();OCCUPIED.store(false,Ordering::Release);Ok(())
    }
}
