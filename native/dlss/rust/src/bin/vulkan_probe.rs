#[cfg(feature="vulkan")]
fn main(){
    use framescale_dlss::{Extent,shared::{ImageKind,SharedImage},vulkan::InstanceContext};
    fn hex(u:[u8;16])->String{u.iter().map(|b|format!("{b:02x}")).collect()}
    fn uuid(s:&str)->Option<[u8;16]>{
        if s.len()!=32||!s.is_ascii(){return None}
        let mut u=[0;16];for (i,b) in u.iter_mut().enumerate(){*b=u8::from_str_radix(&s[i*2..i*2+2],16).ok()?;}Some(u)
    }
    let args=std::env::args().skip(1).collect::<Vec<_>>();
    let run=||->Result<(),String>{
        let ctx=InstanceContext::new(&[]).map_err(|e|format!("{e:?}"))?;
        for a in ctx.inventory().map_err(|e|format!("{e:?}"))?{
            println!("{} vendor={:04x} device={:04x} UUID={} driver={} queue={:?} formats={:?}",
                a.name,a.identity.vendor,a.device_id,hex(a.identity.device),hex(a.identity.driver),a.queue_family,a.formats);
        }
        let device=match args.as_slice(){
            []=>return Ok(()),
            [mode] if mode=="--smoke"||mode=="--resources"=>ctx.create_smoke(),
            [mode,gl,driver] if mode=="--match"||mode=="--export"=>{
                let gl=uuid(gl).ok_or("invalid GL UUID")?;
                let driver=uuid(driver).ok_or("invalid GL driver UUID")?;
                ctx.create_matched(&[gl],driver,&[])
            }
            _=>return Err("usage: vulkan_probe [--smoke | --resources | --match GL_UUID DRIVER_UUID | --export GL_UUID DRIVER_UUID]".into()),
        }.map_err(|e|format!("{e:?}"))?;
        // No live resources or previous submissions exist at this point.
        unsafe{device.smoke_submit()}.map_err(|e|format!("{e:?}"))?;
        println!("PASS: logical device + queue + command buffer + fence on {}",device.adapter().name);
        if args[0]=="--resources"||args[0]=="--export"{
            let external=args[0]=="--export";
            for kind in [ImageKind::Rgba8,ImageKind::HdrInput,ImageKind::HdrOutput,ImageKind::DepthCopy,ImageKind::Motion]{
                let size=Extent{w:64,h:48};
                let mut image=if external{SharedImage::external(&device,size,kind)}else{SharedImage::local(&device,size,kind)}
                    .map_err(|e|format!("{e:?}"))?;
                if external{
                    let fds=image.export().map_err(|e|format!("{e:?}"))?;
                    assert!(fds.allocation_size>0);
                    drop(fds); // No import; Rust closes all three descriptors.
                }
                println!("PASS: {:?} allocation {} bytes; external export={}",kind,image.allocation_size(),external);
                // No handles have been submitted or imported.
                unsafe{image.close()};
            }
        }
        unsafe{device.close()};
        Ok(())
    };
    if let Err(e)=run(){eprintln!("Vulkan probe failed: {e}");std::process::exit(1)}
}
#[cfg(not(feature="vulkan"))]
fn main(){eprintln!("Build with --features vulkan");std::process::exit(2)}
