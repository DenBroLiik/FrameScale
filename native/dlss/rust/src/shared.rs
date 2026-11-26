//! Linux Vulkan-side shared image allocation and opaque FD export.
//! GL import, queue-family transfers and frame scheduling are separate contracts.
use crate::{Extent, vulkan::{DeviceContext,VkError}};
use ash::vk;
use std::os::fd::{OwnedFd,FromRawFd};
type Result<T>=std::result::Result<T,VkError>;

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum ImageKind { Rgba8, HdrInput, HdrOutput, DepthCopy, Motion }
impl ImageKind {
    fn format(self)->vk::Format{
        match self {
            Self::Rgba8=>vk::Format::R8G8B8A8_UNORM,
            Self::HdrInput|Self::HdrOutput=>vk::Format::R16G16B16A16_SFLOAT,
            Self::DepthCopy=>vk::Format::R32_SFLOAT,
            Self::Motion=>vk::Format::R16G16_SFLOAT,
        }
    }
    fn usage(self)->vk::ImageUsageFlags{
        let base=vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_SRC|vk::ImageUsageFlags::TRANSFER_DST;
        if self==Self::HdrOutput{base|vk::ImageUsageFlags::STORAGE}else{base}
    }
    fn supported(self,ctx:&DeviceContext)->bool{
        ctx.supports_export(self.format(),self.usage())
    }
}
fn memory_type(bits:u32,flags:&[vk::MemoryPropertyFlags])->Option<u32>{
    let mut fallback=None;
    for (i,f) in flags.iter().take(32).enumerate(){
        if bits&(1u32<<i)==0{continue}
        if f.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL){return Some(i as u32)}
        if fallback.is_none(){fallback=Some(i as u32)}
    }
    fallback
}
/// Initialization rollback only: no GPU work has been recorded or submitted.
struct Alloc<'a>{
    ctx:&'a DeviceContext,image:vk::Image,memory:vk::DeviceMemory,view:vk::ImageView,
    to_gl:vk::Semaphore,to_vk:vk::Semaphore,
}
impl Drop for Alloc<'_>{
    fn drop(&mut self){
        let d=self.ctx.raw_device();
        unsafe{
            if self.to_vk!=vk::Semaphore::null(){d.destroy_semaphore(self.to_vk,None)}
            if self.to_gl!=vk::Semaphore::null(){d.destroy_semaphore(self.to_gl,None)}
            if self.view!=vk::ImageView::null(){d.destroy_image_view(self.view,None)}
            if self.image!=vk::Image::null(){d.destroy_image(self.image,None)}
            if self.memory!=vk::DeviceMemory::null(){d.free_memory(self.memory,None)}
        }
    }
}
#[derive(Clone,Copy,Debug)]
pub struct Handles{
    pub image:vk::Image,pub view:vk::ImageView,pub to_gl:vk::Semaphore,pub to_vk:vk::Semaphore,
}
/// Descriptors close automatically on error or drop until ownership is explicitly
/// transferred to a successful importer. No CPU mapping of the shared allocation.
pub struct ExportedFds{
    pub memory:OwnedFd,pub to_gl:OwnedFd,pub to_vk:OwnedFd,
    pub allocation_size:u64,pub extent:Extent,pub kind:ImageKind,
}
/// All allocations are dedicated; context borrow prevents normal device close
/// while this object is live. Once handles/FDs are exposed, explicit retirement
/// and close are mandatory; Drop deliberately leaks rather than freeing in-flight work.
#[must_use]
pub struct SharedImage<'a>{
    alloc:Option<Alloc<'a>>,extent:Extent,kind:ImageKind,
    allocation_size:u64,external:bool,exposed:bool,
}
impl<'a> SharedImage<'a>{
    pub fn local(ctx:&'a DeviceContext,extent:Extent,kind:ImageKind)->Result<Self>{
        Self::new(ctx,extent,kind,false)
    }
    pub fn external(ctx:&'a DeviceContext,extent:Extent,kind:ImageKind)->Result<Self>{
        Self::new(ctx,extent,kind,true)
    }
    fn new(ctx:&'a DeviceContext,extent:Extent,kind:ImageKind,external:bool)->Result<Self>{
        if !extent.valid(){return Err(VkError::Invalid("shared extent"))}
        if external&&(!ctx.fd_enabled()||!ctx.adapter().formats.binary_semaphore||!kind.supported(ctx)){
            return Err(VkError::Invalid("external FD image/semaphore capability unavailable"))
        }
        let d=ctx.raw_device();
        let mut alloc=Alloc{ctx,image:vk::Image::null(),memory:vk::DeviceMemory::null(),view:vk::ImageView::null(),
            to_gl:vk::Semaphore::null(),to_vk:vk::Semaphore::null()};
        let mut ext=vk::ExternalMemoryImageCreateInfo::default().handle_types(vk::ExternalMemoryHandleTypeFlags::OPAQUE_FD);
        let mut info=vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D).format(kind.format())
            .extent(vk::Extent3D{width:extent.w,height:extent.h,depth:1}).mip_levels(1).array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL).usage(kind.usage())
            .sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED);
        if external{info=info.push_next(&mut ext)}
        // Validate exact extent/usage before creation, including the external chain.
        let mut format_ext=vk::PhysicalDeviceExternalImageFormatInfo::default()
            .handle_type(vk::ExternalMemoryHandleTypeFlags::OPAQUE_FD);
        let mut query=vk::PhysicalDeviceImageFormatInfo2::default().format(kind.format()).ty(vk::ImageType::TYPE_2D)
            .tiling(vk::ImageTiling::OPTIMAL).usage(kind.usage());
        if external{query=query.push_next(&mut format_ext)}
        let mut props=vk::ImageFormatProperties2::default();
        unsafe{ctx.raw_instance().get_physical_device_image_format_properties2(ctx.physical(),&query,&mut props)}?;
        let max=props.image_format_properties.max_extent;
        if extent.w>max.width||extent.h>max.height{return Err(VkError::Invalid("device image extent"))}
        alloc.image=unsafe{d.create_image(&info,None)}?;
        let req=unsafe{d.get_image_memory_requirements(alloc.image)};
        let mem=unsafe{ctx.raw_instance().get_physical_device_memory_properties(ctx.physical())};
        let flags=mem.memory_types[..mem.memory_type_count as usize].iter().map(|m|m.property_flags).collect::<Vec<_>>();
        let index=memory_type(req.memory_type_bits,&flags).ok_or(VkError::Invalid("no compatible memory"))?;
        let mut dedicated=vk::MemoryDedicatedAllocateInfo::default().image(alloc.image);
        let mut export=vk::ExportMemoryAllocateInfo::default().handle_types(vk::ExternalMemoryHandleTypeFlags::OPAQUE_FD);
        let mut mi=vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(index).push_next(&mut dedicated);
        if external{mi=mi.push_next(&mut export)}
        alloc.memory=unsafe{d.allocate_memory(&mi,None)}?;
        unsafe{d.bind_image_memory(alloc.image,alloc.memory,0)}?;
        let range=vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR)
            .level_count(1).layer_count(1);
        let vi=vk::ImageViewCreateInfo::default().image(alloc.image).view_type(vk::ImageViewType::TYPE_2D)
            .format(kind.format()).subresource_range(range);
        alloc.view=unsafe{d.create_image_view(&vi,None)}?;
        let mut sem_ext=vk::ExportSemaphoreCreateInfo::default().handle_types(vk::ExternalSemaphoreHandleTypeFlags::OPAQUE_FD);
        let mut si=vk::SemaphoreCreateInfo::default();
        if external{si=si.push_next(&mut sem_ext)}
        alloc.to_gl=unsafe{d.create_semaphore(&si,None)}?;
        alloc.to_vk=unsafe{d.create_semaphore(&si,None)}?;
        Ok(Self{alloc:Some(alloc),extent,kind,allocation_size:req.size,external,exposed:false})
    }
    pub fn allocation_size(&self)->u64{self.allocation_size}
    /// Handles do not transfer ownership. Once exposed, explicit close is required.
    pub fn handles(&mut self)->Handles{
        self.exposed=true;
        let a=self.alloc.as_ref().expect("live allocation");
        Handles{image:a.image,view:a.view,to_gl:a.to_gl,to_vk:a.to_vk}
    }
    pub fn export(&mut self)->Result<ExportedFds>{
        if !self.external{return Err(VkError::Invalid("not an external allocation"))}
        let a=self.alloc.as_ref().ok_or(VkError::Invalid("closed allocation"))?;
        let mem=ash::khr::external_memory_fd::Device::new(a.ctx.raw_instance(),a.ctx.raw_device());
        let sem=ash::khr::external_semaphore_fd::Device::new(a.ctx.raw_instance(),a.ctx.raw_device());
        let mi=vk::MemoryGetFdInfoKHR::default().memory(a.memory).handle_type(vk::ExternalMemoryHandleTypeFlags::OPAQUE_FD);
        let fd=unsafe{mem.get_memory_fd(&mi)}?;
        if fd<0{return Err(VkError::Invalid("memory FD"))}
        let memory=unsafe{OwnedFd::from_raw_fd(fd)};
        let get=|s|{
            let i=vk::SemaphoreGetFdInfoKHR::default().semaphore(s).handle_type(vk::ExternalSemaphoreHandleTypeFlags::OPAQUE_FD);
            let fd=unsafe{sem.get_semaphore_fd(&i)}?;
            if fd<0{return Err(VkError::Invalid("semaphore FD"))}
            Ok(unsafe{OwnedFd::from_raw_fd(fd)})
        };
        let to_gl=get(a.to_gl)?;let to_vk=get(a.to_vk)?;
        self.exposed=true;
        Ok(ExportedFds{memory,to_gl,to_vk,allocation_size:self.allocation_size,extent:self.extent,kind:self.kind})
    }
    /// # Safety
    /// All work using this allocation completed or was discarded, all GL imports
    /// and external users released. No command buffer may still reference it.
    pub unsafe fn close(mut self){drop(self.alloc.take());}
}
impl Drop for SharedImage<'_>{
    fn drop(&mut self){
        if self.exposed{
            if let Some(a)=self.alloc.take(){std::mem::forget(a)}
        }
        // Unexposed allocations have never been submitted: ordinary rollback is safe.
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn choose_memory(){
        let f=[vk::MemoryPropertyFlags::HOST_VISIBLE,vk::MemoryPropertyFlags::DEVICE_LOCAL];
        assert_eq!(memory_type(3,&f),Some(1));assert_eq!(memory_type(1,&f),Some(0));
        assert_eq!(memory_type(0,&f),None);
    }
    #[test]fn format_usage(){
        assert_eq!(ImageKind::DepthCopy.format().as_raw(),vk::Format::R32_SFLOAT.as_raw());
        assert!(ImageKind::HdrOutput.usage().contains(vk::ImageUsageFlags::STORAGE));
        assert!(!ImageKind::HdrInput.usage().contains(vk::ImageUsageFlags::STORAGE));
    }
}
