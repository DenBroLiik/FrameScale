#[cfg(feature="vulkan")]
fn main(){
    use framescale_dlss::{Extent,shared::{SharedImage,ImageKind},vulkan::InstanceContext,scheduler::{Scheduler,Poll}};
    use std::time::{Instant,Duration};
    let run=||->Result<(),String>{
        let dev=InstanceContext::new(&[]).map_err(|e|format!("{e:?}"))?.create_smoke().map_err(|e|format!("{e:?}"))?;
        // Each slot owns its own reusable image. Never expose it to another API.
        let images=(0..3).map(|_|SharedImage::local(&dev,Extent{w:64,h:48},ImageKind::HdrOutput))
            .collect::<Result<Vec<_>,_>>().map_err(|e|format!("{e:?}"))?;
        let mut s=Scheduler::new(&dev,3).map_err(|e|format!("{e:?}"))?;
        let deadline=Instant::now()+Duration::from_secs(10);
        let mut pending=Vec::with_capacity(3);
        let mut submitted=0;let mut retired=0;let mut busy=0;
        let err=|e|format!("{e:?}");
        // Fill ring before polling. Commands are empty; images test lifetime only.
        while retired<128{
            if Instant::now()>deadline{return Err("scheduler deadline exceeded".into())}
            while submitted<128{
                let Some((ticket,_))=s.try_begin().map_err(err)? else{busy+=1;break};
                assert!(ticket.index()<images.len());
                unsafe{s.submit(ticket,&[],&[],&[])}.map_err(err)?;
                pending.push(ticket);submitted+=1;
            }
            let mut i=0;
            while i<pending.len(){
                if s.poll(pending[i]).map_err(err)?==Poll::Ready{
                    let t=pending.remove(i);
                    // No external consumer in this test.
                    unsafe{s.release(t)}.map_err(err)?;
                    retired+=1;
                }else{i+=1}
            }
            if !pending.is_empty(){std::thread::sleep(Duration::from_micros(100))}
        }
        if busy==0||!s.idle(){return Err("scheduler did not exercise saturation or drain".into())}
        s.close().map_err(err)?;drop(s);drop(images);
        unsafe{dev.close()};
        println!("PASS: {submitted} submissions, {retired} retired, three reused slots, {busy} busy returns");
        Ok(())
    };
    if let Err(e)=run(){eprintln!("Scheduler probe failed: {e}");std::process::exit(1)}
}
#[cfg(not(feature="vulkan"))]
fn main(){eprintln!("--features vulkan required");std::process::exit(2)}
