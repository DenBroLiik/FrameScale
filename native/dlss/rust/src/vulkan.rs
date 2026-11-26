//! Vulkan 1.1+ device inventory and explicit resource ownership.
//! This module creates diagnostic devices, not NGX-ready devices.
use crate::{DeviceIdentity, Uuid, select_device};
use ash::{Entry, Instance, Device, vk};
use std::{ffi::{CStr,CString}, marker::PhantomData, mem::ManuallyDrop, rc::Rc};
use vk::Handle;

#[derive(Debug)]
pub enum VkError {
    Vulkan(i32), Invalid(&'static str), MissingExtension(String), Match(crate::Error),
}
impl From<vk::Result> for VkError {
    fn from(v:vk::Result)->Self{Self::Vulkan(v.as_raw())}
}
type Result<T>=std::result::Result<T,VkError>;

#[derive(Clone,Debug)]
pub struct Adapter {
    pub identity:DeviceIdentity,
    pub name:String,
    pub api_version:u32,
    pub device_id:u32,
    pub queue_family:Option<u32>,
    pub extensions:Vec<String>,
    pub formats:ExternalFormats,
    physical:vk::PhysicalDevice,
}
#[derive(Clone,Copy,Debug,Default)]
pub struct ExternalFormats {
    pub binary_semaphore:bool,
    pub rgba8:bool,
    pub hdr_input:bool,
    pub hdr_storage:bool,
    pub depth_copy:bool,
    pub motion:bool,
}
impl ExternalFormats {
    pub fn dlss_candidates(self)->bool {
        self.binary_semaphore&&self.hdr_input&&self.hdr_storage&&self.depth_copy&&self.motion
    }
}
const FD_EXTENSIONS:[&CStr;2]=[c"VK_KHR_external_memory_fd",c"VK_KHR_external_semaphore_fd"];
fn extensions(required:&[&CStr],supported:&[String])->Result<Vec<CString>> {
    let mut result=Vec::new();
    for &name in required {
        let n=name.to_str().map_err(|_|VkError::Invalid("non-UTF8 extension"))?;
        if n.is_empty()||!supported.iter().any(|s|s==n){return Err(VkError::MissingExtension(n.into()))}
        if !result.iter().any(|s:&CString|s.as_c_str()==name){result.push(name.to_owned())}
    }
    Ok(result)
}

pub struct InstanceContext {
    _entry:Entry, instance:Instance,
    _thread:PhantomData<Rc<()>>,
}
impl InstanceContext {
    pub fn new(required:&[&CStr])->Result<Self>{
        let entry=Entry::linked();
        let version=unsafe{entry.try_enumerate_instance_version()}?.unwrap_or(vk::API_VERSION_1_0);
        if version<vk::API_VERSION_1_1{return Err(VkError::Invalid("Vulkan loader < 1.1"))}
        let props=unsafe{entry.enumerate_instance_extension_properties(None)}?;
        let supported=props.iter().map(|p|unsafe{CStr::from_ptr(p.extension_name.as_ptr())}.to_string_lossy().into_owned()).collect::<Vec<_>>();
        let names=extensions(required,&supported)?;
        let pointers=names.iter().map(|n|n.as_ptr()).collect::<Vec<_>>();
        let app=vk::ApplicationInfo::default().application_name(c"FrameScale Vulkan diagnostic")
            .application_version(1).api_version(version.min(vk::API_VERSION_1_3));
        let ci=vk::InstanceCreateInfo::default().application_info(&app).enabled_extension_names(&pointers);
        let instance=unsafe{entry.create_instance(&ci,None)}?;
        Ok(Self{_entry:entry,instance,_thread:PhantomData})
    }
    pub fn inventory(&self)->Result<Vec<Adapter>>{
        let physical=unsafe{self.instance.enumerate_physical_devices()}?;
        if physical.len()>256{return Err(VkError::Invalid("physical device count"))}
        let mut out=Vec::new();
        for gpu in physical{
            let core=unsafe{self.instance.get_physical_device_properties(gpu)};
            if core.api_version<vk::API_VERSION_1_1{continue}
            let mut id=vk::PhysicalDeviceIDProperties::default();
            let mut props=vk::PhysicalDeviceProperties2::default().push_next(&mut id);
            unsafe{self.instance.get_physical_device_properties2(gpu,&mut props)};
            let queues=unsafe{self.instance.get_physical_device_queue_family_properties(gpu)};
            let family=queues.iter().position(|q|q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::GRAPHICS|vk::QueueFlags::COMPUTE))
                .map(|n|n as u32);
            let exts=unsafe{self.instance.enumerate_device_extension_properties(gpu)}?;
            let exts=exts.iter().map(|p|unsafe{CStr::from_ptr(p.extension_name.as_ptr())}.to_string_lossy().into_owned()).collect();
            out.push(Adapter{
                identity:DeviceIdentity{device:id.device_uuid,driver:id.driver_uuid,vendor:core.vendor_id},
                name:unsafe{CStr::from_ptr(core.device_name.as_ptr())}.to_string_lossy().into_owned(),
                api_version:core.api_version,device_id:core.device_id,queue_family:family,
                extensions:exts,formats:self.formats(gpu),physical:gpu,
            });
        }
        Ok(out)
    }
    fn exportable(&self,gpu:vk::PhysicalDevice,format:vk::Format,usage:vk::ImageUsageFlags)->bool{
        let handle=vk::ExternalMemoryHandleTypeFlags::OPAQUE_FD;
        let mut external=vk::PhysicalDeviceExternalImageFormatInfo::default().handle_type(handle);
        let query=vk::PhysicalDeviceImageFormatInfo2::default().format(format)
            .ty(vk::ImageType::TYPE_2D).tiling(vk::ImageTiling::OPTIMAL).usage(usage).push_next(&mut external);
        let mut ext=vk::ExternalImageFormatProperties::default();
        let mut result=vk::ImageFormatProperties2::default().push_next(&mut ext);
        if unsafe{self.instance.get_physical_device_image_format_properties2(gpu,&query,&mut result)}.is_err(){return false}
        let p=ext.external_memory_properties;
        p.external_memory_features.contains(vk::ExternalMemoryFeatureFlags::EXPORTABLE)
            &&p.compatible_handle_types.contains(handle)
    }
    fn formats(&self,gpu:vk::PhysicalDevice)->ExternalFormats{
        let handle=vk::ExternalSemaphoreHandleTypeFlags::OPAQUE_FD;
        let query=vk::PhysicalDeviceExternalSemaphoreInfo::default().handle_type(handle);
        let mut result=vk::ExternalSemaphoreProperties::default();
        unsafe{self.instance.get_physical_device_external_semaphore_properties(gpu,&query,&mut result)};
        let input=vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_SRC|vk::ImageUsageFlags::TRANSFER_DST;
        ExternalFormats{
            binary_semaphore:result.external_semaphore_features.contains(vk::ExternalSemaphoreFeatureFlags::EXPORTABLE)
                &&result.compatible_handle_types.contains(handle),
            rgba8:self.exportable(gpu,vk::Format::R8G8B8A8_UNORM,input),
            hdr_input:self.exportable(gpu,vk::Format::R16G16B16A16_SFLOAT,input),
            hdr_storage:self.exportable(gpu,vk::Format::R16G16B16A16_SFLOAT,input|vk::ImageUsageFlags::STORAGE),
            depth_copy:self.exportable(gpu,vk::Format::R32_SFLOAT,input),
            motion:self.exportable(gpu,vk::Format::R16G16_SFLOAT,input),
        }
    }
    /// Requires exact GL device AND driver UUIDs. Never falls back to a different GPU.
    /// Enables requested extensions plus FD interop extensions, but no optional feature bits.
    /// This is a diagnostic logical device, not sufficient to initialize NGX.
    pub fn create_matched(self,gl:&[Uuid],driver:Uuid,required:&[&CStr])->Result<DeviceContext>{
        let adapters=self.inventory()?;
        let ids=adapters.iter().map(|a|a.identity).collect::<Vec<_>>();
        let i=select_device(gl,driver,&ids).map_err(VkError::Match)?;
        let a=&adapters[i];
        if !a.formats.dlss_candidates(){return Err(VkError::Invalid("external image/semaphore candidate formats unavailable"))}
        let mut exts=required.to_vec();exts.extend(FD_EXTENSIONS);
        self.create(a,&exts)
    }
    /// Explicit non-NVIDIA smoke test path; never used by create_matched.
    pub fn create_smoke(self)->Result<DeviceContext>{
        let adapters=self.inventory()?;
        let a=adapters.iter().find(|a|a.queue_family.is_some()).ok_or(VkError::Invalid("no graphics+compute queue"))?;
        self.create(a,&[])
    }
    fn create(self,a:&Adapter,required:&[&CStr])->Result<DeviceContext>{
        let family=a.queue_family.ok_or(VkError::Invalid("no graphics+compute queue"))?;
        let names=extensions(required,&a.extensions)?;
        let pointers=names.iter().map(|s|s.as_ptr()).collect::<Vec<_>>();
        let priorities=[1.0];
        let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priorities)];
        let info=vk::DeviceCreateInfo::default().queue_create_infos(&queues).enabled_extension_names(&pointers);
        let device=unsafe{self.instance.create_device(a.physical,&info,None)}?;
        let queue=unsafe{device.get_device_queue(family,0)};
        if queue==vk::Queue::null(){unsafe{device.destroy_device(None)};return Err(VkError::Invalid("null queue"))}
        Ok(DeviceContext{instance:ManuallyDrop::new(self),device,physical:a.physical,queue,family,
            adapter:a.clone(),enabled_extensions:names,_thread:PhantomData})
    }
}
impl Drop for InstanceContext {
    fn drop(&mut self){unsafe{self.instance.destroy_instance(None)}}
}

/// Explicitly closed after all child resources are destroyed and GPU work retires.
/// Dropping without close intentionally leaks the device+instance rather than
/// destroying possibly live GPU work. Dedicated diagnostic processes only for now.
#[must_use]
pub struct DeviceContext {
    instance:ManuallyDrop<InstanceContext>,device:Device,
    physical:vk::PhysicalDevice,queue:vk::Queue,family:u32,
    adapter:Adapter,enabled_extensions:Vec<CString>,_thread:PhantomData<Rc<()>>,
}
impl DeviceContext {
    pub fn adapter(&self)->&Adapter{&self.adapter}
    pub(crate) fn raw_instance(&self)->&Instance{&self.instance.instance}
    pub(crate) fn raw_device(&self)->&Device{&self.device}
    pub(crate) fn physical(&self)->vk::PhysicalDevice{self.physical}
    pub(crate) fn queue(&self)->vk::Queue{self.queue}
    pub(crate) fn family(&self)->u32{self.family}
    pub(crate) fn fd_enabled(&self)->bool{
        FD_EXTENSIONS.iter().all(|n|self.enabled_extensions.iter().any(|s|s.as_c_str()==*n))
    }
    pub(crate) fn supports_export(&self,format:vk::Format,usage:vk::ImageUsageFlags)->bool{
        self.instance.exportable(self.physical,format,usage)
    }
    pub fn handles(&self)->(u64,u64,u64,u64){
        (self.instance.instance.handle().as_raw(),self.physical.as_raw(),self.device.handle().as_raw(),self.queue.as_raw())
    }
    /// # Safety
    /// Exclusive queue access, no outstanding submission, no other child objects.
    /// On failure, abandon this diagnostic process; do not call close, because
    /// work may be in flight and pool/fence may still be owned by the device.
    pub unsafe fn smoke_submit(&self)->Result<()>{
        let pool_info=vk::CommandPoolCreateInfo::default().queue_family_index(self.family);
        let pool=unsafe{self.device.create_command_pool(&pool_info,None)}?;
        let alloc=vk::CommandBufferAllocateInfo::default().command_pool(pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1);
        let cmd=unsafe{self.device.allocate_command_buffers(&alloc)}?[0];
        let begin=vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        unsafe{self.device.begin_command_buffer(cmd,&begin)?;self.device.end_command_buffer(cmd)?;}
        let fence=unsafe{self.device.create_fence(&vk::FenceCreateInfo::default(),None)}?;
        let cmds=[cmd];
        let submit=[vk::SubmitInfo::default().command_buffers(&cmds)];
        unsafe{
            self.device.queue_submit(self.queue,&submit,fence)?;
            self.device.wait_for_fences(&[fence],true,5_000_000_000)?;
            self.device.destroy_fence(fence,None);self.device.destroy_command_pool(pool,None);
        }
        Ok(())
    }
    /// # Safety
    /// All GPU submissions completed and all child objects, including NGX, released.
    pub unsafe fn close(mut self){
        unsafe{self.device.destroy_device(None);ManuallyDrop::drop(&mut self.instance);}
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test] fn extension_validation(){
        let supported=vec!["a".into(),"b".into()];
        assert_eq!(extensions(&[c"a",c"b",c"a"],&supported).unwrap().len(),2);
        assert!(matches!(extensions(&[c"missing"],&supported),Err(VkError::MissingExtension(_))));
        assert!(matches!(extensions(&[c""],&supported),Err(VkError::MissingExtension(_))));
    }
}
