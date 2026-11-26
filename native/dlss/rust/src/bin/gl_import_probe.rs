#[cfg(all(feature="gl-interop",target_os="linux"))]
mod probe {
    use framescale_dlss::{Extent,gl_interop::{GlContext,GlError,ImportedImage},shared::{SharedImage,ImageKind},vulkan::InstanceContext};
    use std::ffi::c_void;
    type P=*mut c_void;
    #[link(name="EGL")]
    unsafe extern "C"{
        fn eglGetDisplay(native:P)->P;
        fn eglInitialize(d:P,major:*mut i32,minor:*mut i32)->u32;
        fn eglBindAPI(api:u32)->u32;
        fn eglChooseConfig(d:P,attr:*const i32,cfg:*mut P,size:i32,count:*mut i32)->u32;
        fn eglCreateContext(d:P,cfg:P,share:P,attr:*const i32)->P;
        fn eglMakeCurrent(d:P,draw:P,read:P,ctx:P)->u32;
        fn eglDestroyContext(d:P,ctx:P)->u32;
        fn eglTerminate(d:P)->u32;
    }
    struct Egl{display:P,context:P}
    impl Egl{
        fn new()->Option<Self>{
            let n=std::ptr::null_mut();
            unsafe{
                let display=eglGetDisplay(n);
                if display.is_null()||eglInitialize(display,n.cast(),n.cast())==0{return None}
                let mut e=Self{display,context:n};
                if eglBindAPI(0x30a2)==0{return None}
                let attr=[0x3033,1,0x3040,8,0x3038];
                let mut cfg=n;let mut count=0;
                if eglChooseConfig(display,attr.as_ptr(),&mut cfg,1,&mut count)==0||count!=1{return None}
                let ca=[0x3098,4,0x30fb,5,0x30fd,1,0x3038];
                e.context=eglCreateContext(display,cfg,n,ca.as_ptr());
                if e.context.is_null()||eglMakeCurrent(display,n,n,e.context)==0{return None}
                Some(e)
            }
        }
    }
    impl Drop for Egl{
        fn drop(&mut self){unsafe{
            let n=std::ptr::null_mut();
            eglMakeCurrent(self.display,n,n,n);
            if !self.context.is_null(){eglDestroyContext(self.display,self.context);}
            eglTerminate(self.display);
        }}
    }
    pub fn run()->i32{
        let Some(_egl)=Egl::new()else{eprintln!("SKIP: no EGL OpenGL 4.5");return 77};
        let gl=match unsafe{GlContext::capture()}{
            Ok(g)=>g,
            Err(GlError::Unsupported(s))=>{eprintln!("SKIP: {s}");return 77},
            Err(e)=>{eprintln!("GL capture failed: {e:?}");return 1}
        };
        let result=(||->Result<(),String>{
            let vk=InstanceContext::new(&[]).map_err(|e|format!("{e:?}"))?;
            let dev=vk.create_matched(gl.devices(),gl.driver(),&[]).map_err(|e|format!("{e:?}"))?;
            for k in [ImageKind::Rgba8,ImageKind::HdrInput,ImageKind::HdrOutput,ImageKind::DepthCopy,ImageKind::Motion]{
                let im=SharedImage::external(&dev,Extent{w:64,h:48},k).map_err(|e|format!("{e:?}"))?;
                let pair=ImportedImage::new(&gl,im).map_err(|e|format!("{e:?}"))?;
                // Import only. Never submit, wait on an unsignaled semaphore or read pixels.
                unsafe{pair.close()}.map_err(|e|format!("{e:?}"))?;
                println!("PASS: {:?} Vulkan allocation + GL texture/semaphore import",k);
            }
            unsafe{dev.close()};
            Ok(())
        })();
        match result{Ok(())=>0,Err(e)=>{eprintln!("Import probe failed: {e}");1}}
    }
}
#[cfg(all(feature="gl-interop",target_os="linux"))]
fn main(){std::process::exit(probe::run())}
#[cfg(not(all(feature="gl-interop",target_os="linux")))]
fn main(){eprintln!("Linux and --features gl-interop required");std::process::exit(2)}
