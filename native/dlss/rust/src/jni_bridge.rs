//! JNI ABI 1, Linux x86_64. Only a read-only Vulkan inventory on the Java worker.
//! No GL calls, device creation, NGX, external memory allocation or queue submission.
use crate::{Extent,Uuid,vulkan::{Adapter,InstanceContext}};
use jni_sys::{JNIEnv,jclass,jint,jboolean,jbyteArray,jstring,JNI_TRUE,JNI_FALSE};
use std::{ffi::CString,panic::catch_unwind,ptr::null_mut};

fn parse_ids(devices:&[u8],driver:&[u8])->Option<(Vec<Uuid>,Uuid)>{
    if devices.is_empty()||devices.len()>256||devices.len()%16!=0||driver.len()!=16{return None}
    let ids=devices.chunks_exact(16).map(|s|s.try_into().expect("16-byte chunk")).collect::<Vec<Uuid>>();
    let driver:Uuid=driver.try_into().ok()?;
    if driver==[0;16]||ids.contains(&[0;16]){return None}
    Some((ids,driver))
}
fn matching<'a>(ids:&[Uuid],driver:Uuid,adapters:&'a [Adapter])->Result<Option<&'a Adapter>,&'static str>{
    let mut found=None;
    for a in adapters{
        if a.identity.driver==driver&&ids.contains(&a.identity.device){
            if found.is_some(){return Err("Ambiguous GL/Vulkan device match")}
            found=Some(a);
        }
    }
    Ok(found)
}
fn yes(b:bool)->&'static str{if b{"yes"}else{"no"}}
fn report(devices:&[u8],driver:&[u8])->String{
    let Some((ids,driver))=parse_ids(devices,driver)else{return "Rust Vulkan | Invalid or zero UUID data".into()};
    let context=match InstanceContext::new(&[]){
        Ok(c)=>c,Err(e)=>return format!("Rust Vulkan | Instance unavailable: {e:?}")
    };
    let adapters=match context.inventory(){
        Ok(a)=>a,Err(e)=>return format!("Rust Vulkan | Device inventory unavailable: {e:?}")
    };
    match matching(&ids,driver,&adapters){
        Ok(Some(a))=>{
            let f=a.formats;
            let fd=["VK_KHR_external_memory_fd","VK_KHR_external_semaphore_fd"].iter().all(|n|a.extensions.iter().any(|x|x==*n));
            let nvidia=a.identity.vendor==0x10de;
            format!("Matched Vulkan device vendor=0x{:04x} device=0x{:04x} API={}.{} | Rust Vulkan | NVIDIA={} | graphics+compute queue={} | FD extensions={} semaphore export={} | RGBA8={} HDR RGBA16F input={} HDR RGBA16F storage output={} R32F depth-copy={} RG16F motion={} | capability query only; no interop/NGX execution",
                a.identity.vendor,a.device_id,ash::vk::api_version_major(a.api_version),ash::vk::api_version_minor(a.api_version),
                yes(nvidia),yes(a.queue_family.is_some()),yes(fd),yes(f.binary_semaphore),yes(f.rgba8),
                yes(f.hdr_input),yes(f.hdr_storage),yes(f.depth_copy),yes(f.motion))
        }
        Ok(None)=>"Rust Vulkan | No Vulkan device matches GL device and driver UUID".into(),
        Err(e)=>format!("Rust Vulkan | {e}"),
    }
}
// JNI receives only JVM-owned pointers. No reference escapes the native call.
// Strings returned by report contain ASCII (numeric properties and fixed labels).
unsafe fn string(env:*mut JNIEnv,msg:&str)->jstring{
    let Some(t)= (unsafe{env.as_ref().and_then(|p|p.as_ref())}) else{return null_mut()};
    let (Some(new),Some(pending))=(t.NewStringUTF,t.ExceptionCheck)else{return null_mut()};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    let Ok(s)=CString::new(msg)else{return null_mut()};
    unsafe{new(env,s.as_ptr())}
}
unsafe fn probe(env:*mut JNIEnv,ids:jbyteArray,driver:jbyteArray)->jstring{
    let Some(t)=(unsafe{env.as_ref().and_then(|p|p.as_ref())})else{return null_mut()};
    let (Some(length),Some(region),Some(pending))=(t.GetArrayLength,t.GetByteArrayRegion,t.ExceptionCheck)else{return null_mut()};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    if ids.is_null()||driver.is_null(){return unsafe{string(env,"Rust Vulkan | Invalid UUID arrays")}}
    let n=unsafe{length(env,ids)};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    let m=unsafe{length(env,driver)};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    if !(16..=256).contains(&n)||n%16!=0||m!=16{
        return unsafe{string(env,"Rust Vulkan | Invalid UUID arrays")}
    }
    let mut bytes=[0u8;256];let mut drv=[0u8;16];
    unsafe{region(env,ids,0,n,bytes.as_mut_ptr().cast())};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    unsafe{region(env,driver,0,16,drv.as_mut_ptr().cast())};
    if unsafe{pending(env)}!=JNI_FALSE{return null_mut()}
    unsafe{string(env,&report(&bytes[..n as usize],&drv))}
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_by_xopilot_framescale_NativeBridge_nativeAbi(_: *mut JNIEnv,_:jclass)->jint{1}
#[unsafe(no_mangle)]
pub extern "system" fn Java_by_xopilot_framescale_NativeBridge_nativeValidExtent(_: *mut JNIEnv,_:jclass,w:jint,h:jint)->jboolean{
    if w>0&&h>0&&(Extent{w:w as u32,h:h as u32}).valid(){JNI_TRUE}else{JNI_FALSE}
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_by_xopilot_framescale_NativeBridge_nativeProbe(env:*mut JNIEnv,_:jclass,ids:jbyteArray,driver:jbyteArray)->jstring{
    // No Rust unwind crosses JNI. Driver hangs/crashes and OOM abort are not recoverable.
    match catch_unwind(||unsafe{probe(env,ids,driver)}){
        Ok(s)=>s,Err(_)=>unsafe{string(env,"Rust Vulkan | Native diagnostic panic")}
    }
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn uuid_validation(){
        assert!(parse_ids(&[0;16],&[2;16]).is_none());
        assert!(parse_ids(&[1;17],&[2;16]).is_none());
        assert!(parse_ids(&[1;16],&[0;16]).is_none());
        assert!(parse_ids(&[1;272],&[2;16]).is_none());
        assert_eq!(parse_ids(&[1;32],&[2;16]).unwrap(),(vec![[1;16];2],[2;16]));
    }
}
