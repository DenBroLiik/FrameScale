#[cfg(all(feature="vulkan",target_os="linux"))]
fn main(){
    use framescale_dlss::{Extent,pipeline::Pipeline,vulkan::InstanceContext,scheduler::Poll};
    use std::time::{Instant,Duration};
    let run=||->Result<(),String>{
        let dev=InstanceContext::new(&[]).map_err(|e|format!("{e:?}"))?.create_smoke().map_err(|e|format!("{e:?}"))?;
        let mut p=Pipeline::new(&dev,Extent{w:32,h:24},Extent{w:64,h:48},3)?;
        let mut pending=Vec::with_capacity(3);let mut submitted=0u32;let mut retired=0u32;let mut busy=0;
        let deadline=Instant::now()+Duration::from_secs(10);
        while retired<128{
            if Instant::now()>deadline{return Err("pipeline deadline exceeded".into())}
            while submitted<128{
                let Some(t)=p.submit(submitted)?else{busy+=1;break};
                pending.push(t);submitted+=1;
            }
            let mut i=0;
            while i<pending.len(){
                let t=pending[i];
                if p.poll(t)?==Poll::Ready{
                    if p.release(t).is_ok(){return Err("unverified frame released".into())}
                    if !p.verify(t)?{return Err("ready fence regressed".into())}
                    // A ready verified frame is still retained until explicit release.
                    if pending.len()==3&&submitted<128&&p.submit(submitted)?.is_some(){return Err("consumer-owned slot reused".into())}
                    p.release(t)?;pending.remove(i);retired+=1;
                }else{i+=1}
            }
            if !pending.is_empty(){std::thread::sleep(Duration::from_micros(100))}
        }
        if busy==0{return Err("backpressure not exercised".into())}
        let bytes=p.allocation_size();p.close()?;drop(p);unsafe{dev.close()};
        println!("PASS: {retired} synthetic HDR frames, {} output pixels verified, {bytes} allocation bytes, {busy} busy returns",u64::from(retired)*64*48);
        Ok(())
    };
    if let Err(e)=run(){eprintln!("Pipeline failed: {e}");std::process::exit(1)}
}
#[cfg(not(all(feature="vulkan",target_os="linux")))]
fn main(){eprintln!("Linux and --features vulkan required");std::process::exit(2)}
