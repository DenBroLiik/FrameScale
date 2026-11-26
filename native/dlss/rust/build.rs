use std::{env,path::PathBuf,process::Command};
fn main(){
    println!("cargo:rerun-if-changed=ngx_shim.cpp");
    println!("cargo:rerun-if-env-changed=DLSS_SDK");
    if env::var_os("CARGO_FEATURE_NGX").is_none(){return}
    assert_eq!(env::var("CARGO_CFG_TARGET_OS").unwrap(),"linux");
    assert_eq!(env::var("CARGO_CFG_TARGET_ARCH").unwrap(),"x86_64");
    assert_eq!(env::var("HOST").unwrap(),env::var("TARGET").unwrap(),"cross build unsupported");
    let sdk=PathBuf::from(env::var_os("DLSS_SDK").expect("Set DLSS_SDK after accepting applicable SDK terms")).canonicalize().unwrap();
    let out=PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let obj=out.join("ngx_shim.o");
    let lib=out.join("libframescale_ngx_shim.a");
    assert!(sdk.join("include/nvsdk_ngx_vk.h").is_file());
    assert!(sdk.join("lib/Linux_x86_64/libnvsdk_ngx.a").is_file());
    assert!(Command::new("c++").args(["-std=c++20","-O2","-fPIC","-isystem"]).arg(sdk.join("include")).args(["-c","ngx_shim.cpp","-o"]).arg(&obj).status().unwrap().success());
    assert!(Command::new("ar").arg("crs").arg(&lib).arg(&obj).status().unwrap().success());
    println!("cargo:rustc-link-search=native={}",out.display());
    println!("cargo:rustc-link-lib=static=framescale_ngx_shim");
    // SDK itself remains an external dependency; do not bundle its archive.
    println!("cargo:rustc-link-search=native={}",sdk.join("lib/Linux_x86_64").display());
    println!("cargo:rustc-link-lib=static:-bundle=nvsdk_ngx");
    println!("cargo:rustc-link-lib=dylib=vulkan");
    println!("cargo:rustc-link-lib=dylib=stdc++");
    println!("cargo:rustc-link-lib=dylib=dl");
}
