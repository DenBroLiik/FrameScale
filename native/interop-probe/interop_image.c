#define GL_GLEXT_PROTOTYPES 1
#include "interop_image.h"
#include <EGL/egl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define CHECK(c,msg) do {if(!(c)){fprintf(stderr,"interop: %s\n",msg);goto fail;}}while(0)
#define VK(call) do {VkResult e=(call);if(e!=VK_SUCCESS){fprintf(stderr,"interop: %s: %d\n",#call,e);goto fail;}}while(0)
#define PROC(type,var,name) type var=(type)eglGetProcAddress(name);CHECK(var,"missing " name)

int fs_interop_valid_extent(uint32_t w,uint32_t h) {
    return w>0&&h>0&&w<=8192&&h<=8192&&(uint64_t)w*h<=16777216u;
}
void fs_interop_destroy(FsInteropImage *im) {
    if(!im)return;
    if(im->texture)glDeleteTextures(1,&im->texture);
    if(im->gl_to_gl&&im->delete_semaphore)im->delete_semaphore(1,&im->gl_to_gl);
    if(im->gl_to_vk&&im->delete_semaphore)im->delete_semaphore(1,&im->gl_to_vk);
    if(im->gl_memory&&im->delete_memory)im->delete_memory(1,&im->gl_memory);
    if(im->device) {
        if(im->to_gl)vkDestroySemaphore(im->device,im->to_gl,NULL);
        if(im->to_vk)vkDestroySemaphore(im->device,im->to_vk,NULL);
        if(im->image)vkDestroyImage(im->device,im->image,NULL);
        if(im->memory)vkFreeMemory(im->device,im->memory,NULL);
    }
    memset(im,0,sizeof(*im));
}

int fs_interop_create(FsInteropImage *out,VkPhysicalDevice gpu,VkDevice device,uint32_t w,uint32_t h) {
    if(!out||out->device||!gpu||!device||!fs_interop_valid_extent(w,h))return 0;
    FsInteropImage im={.device=device};
    int memfd=-1,to_gl_fd=-1,to_vk_fd=-1;
    CHECK(eglGetCurrentContext()!=EGL_NO_CONTEXT,"no current EGL context");
    CHECK(glGetError()==GL_NO_ERROR,"pre-existing GL error");
    GLint max_texture=0;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&max_texture);
    CHECK(max_texture>0&&w<=(uint32_t)max_texture&&h<=(uint32_t)max_texture,"GL texture limit");
    PROC(PFNGLCREATEMEMORYOBJECTSEXTPROC,create_memory,"glCreateMemoryObjectsEXT");
    PROC(PFNGLMEMORYOBJECTPARAMETERIVEXTPROC,memory_param,"glMemoryObjectParameterivEXT");
    PROC(PFNGLIMPORTMEMORYFDEXTPROC,import_memory,"glImportMemoryFdEXT");
    PROC(PFNGLTEXTURESTORAGEMEM2DEXTPROC,storage,"glTextureStorageMem2DEXT");
    PROC(PFNGLGENSEMAPHORESEXTPROC,gen_semaphores,"glGenSemaphoresEXT");
    PROC(PFNGLIMPORTSEMAPHOREFDEXTPROC,import_semaphore,"glImportSemaphoreFdEXT");
    im.delete_memory=(PFNGLDELETEMEMORYOBJECTSEXTPROC)eglGetProcAddress("glDeleteMemoryObjectsEXT");
    im.delete_semaphore=(PFNGLDELETESEMAPHORESEXTPROC)eglGetProcAddress("glDeleteSemaphoresEXT");
    im.wait_semaphore=(PFNGLWAITSEMAPHOREEXTPROC)eglGetProcAddress("glWaitSemaphoreEXT");
    im.signal_semaphore=(PFNGLSIGNALSEMAPHOREEXTPROC)eglGetProcAddress("glSignalSemaphoreEXT");
    CHECK(im.delete_memory&&im.delete_semaphore&&im.wait_semaphore&&im.signal_semaphore,"missing GL functions");
    PFN_vkGetMemoryFdKHR get_memory_fd=(PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device,"vkGetMemoryFdKHR");
    PFN_vkGetSemaphoreFdKHR get_sem_fd=(PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(device,"vkGetSemaphoreFdKHR");
    CHECK(get_memory_fd&&get_sem_fd,"missing Vulkan FD functions");

    VkPhysicalDeviceExternalImageFormatInfo ext={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkPhysicalDeviceImageFormatInfo2 fi={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,.pNext=&ext,
        .format=VK_FORMAT_R8G8B8A8_UNORM,.type=VK_IMAGE_TYPE_2D,.tiling=VK_IMAGE_TILING_OPTIMAL,
        .usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_SAMPLED_BIT};
    VkExternalImageFormatProperties xp={.sType=VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 fp={.sType=VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,.pNext=&xp};
    VK(vkGetPhysicalDeviceImageFormatProperties2(gpu,&fi,&fp));
    CHECK((xp.externalMemoryProperties.externalMemoryFeatures&VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)
        &&(xp.externalMemoryProperties.compatibleHandleTypes&ext.handleType)
        &&w<=fp.imageFormatProperties.maxExtent.width&&h<=fp.imageFormatProperties.maxExtent.height,"external image unsupported");
    VkPhysicalDeviceExternalSemaphoreInfo si={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkExternalSemaphoreProperties sp={.sType=VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    vkGetPhysicalDeviceExternalSemaphoreProperties(gpu,&si,&sp);
    CHECK((sp.externalSemaphoreFeatures&VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)
        &&(sp.compatibleHandleTypes&si.handleType),"external semaphore unsupported");

    VkExternalMemoryImageCreateInfo ei={.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,.handleTypes=ext.handleType};
    VkImageCreateInfo ii={.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,.pNext=&ei,.imageType=fi.type,.format=fi.format,
        .extent={w,h,1},.mipLevels=1,.arrayLayers=1,.samples=VK_SAMPLE_COUNT_1_BIT,.tiling=fi.tiling,
        .usage=fi.usage,.sharingMode=VK_SHARING_MODE_EXCLUSIVE,.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED};
    VK(vkCreateImage(device,&ii,NULL,&im.image));
    VkMemoryRequirements req;vkGetImageMemoryRequirements(device,im.image,&req);
    VkPhysicalDeviceMemoryProperties mp;vkGetPhysicalDeviceMemoryProperties(gpu,&mp);
    uint32_t mt=UINT32_MAX;
    for(uint32_t j=0;j<mp.memoryTypeCount;j++)if(req.memoryTypeBits&(1u<<j)){
        mt=j;if(mp.memoryTypes[j].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)break;
    }
    CHECK(mt!=UINT32_MAX,"no compatible memory");
    VkMemoryDedicatedAllocateInfo dedicated={.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,.image=im.image};
    VkExportMemoryAllocateInfo em={.sType=VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,.pNext=&dedicated,.handleTypes=ext.handleType};
    VkMemoryAllocateInfo ma={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.pNext=&em,.allocationSize=req.size,.memoryTypeIndex=mt};
    VK(vkAllocateMemory(device,&ma,NULL,&im.memory));VK(vkBindImageMemory(device,im.image,im.memory,0));
    VkMemoryGetFdInfoKHR mf={.sType=VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR,.memory=im.memory,.handleType=ext.handleType};
    VK(get_memory_fd(device,&mf,&memfd));CHECK(memfd>=0,"invalid memory FD");
    VkExportSemaphoreCreateInfo es={.sType=VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,.handleTypes=si.handleType};
    VkSemaphoreCreateInfo sc={.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,.pNext=&es};
    VK(vkCreateSemaphore(device,&sc,NULL,&im.to_gl));VK(vkCreateSemaphore(device,&sc,NULL,&im.to_vk));
    VkSemaphoreGetFdInfoKHR sf={.sType=VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,.semaphore=im.to_gl,.handleType=si.handleType};
    VK(get_sem_fd(device,&sf,&to_gl_fd));CHECK(to_gl_fd>=0,"invalid to-GL FD");
    sf.semaphore=im.to_vk;VK(get_sem_fd(device,&sf,&to_vk_fd));CHECK(to_vk_fd>=0,"invalid to-VK FD");
    create_memory(1,&im.gl_memory);CHECK(im.gl_memory&&glGetError()==GL_NO_ERROR,"GL memory creation");
    GLint yes=GL_TRUE;memory_param(im.gl_memory,GL_DEDICATED_MEMORY_OBJECT_EXT,&yes);
    CHECK(glGetError()==GL_NO_ERROR,"GL dedicated memory");
    import_memory(im.gl_memory,req.size,GL_HANDLE_TYPE_OPAQUE_FD_EXT,memfd);
    CHECK(glGetError()==GL_NO_ERROR,"GL memory import");memfd=-1;
    glCreateTextures(GL_TEXTURE_2D,1,&im.texture);CHECK(im.texture,"GL texture creation");
    glTextureParameteri(im.texture,GL_TEXTURE_TILING_EXT,GL_OPTIMAL_TILING_EXT);
    storage(im.texture,1,GL_RGBA8,(GLsizei)w,(GLsizei)h,im.gl_memory,0);
    CHECK(glGetError()==GL_NO_ERROR,"GL texture storage");
    gen_semaphores(1,&im.gl_to_gl);CHECK(im.gl_to_gl&&glGetError()==GL_NO_ERROR,"GL to-GL semaphore");
    import_semaphore(im.gl_to_gl,GL_HANDLE_TYPE_OPAQUE_FD_EXT,to_gl_fd);
    CHECK(glGetError()==GL_NO_ERROR,"GL to-GL import");to_gl_fd=-1;
    gen_semaphores(1,&im.gl_to_vk);CHECK(im.gl_to_vk&&glGetError()==GL_NO_ERROR,"GL to-VK semaphore");
    import_semaphore(im.gl_to_vk,GL_HANDLE_TYPE_OPAQUE_FD_EXT,to_vk_fd);
    CHECK(glGetError()==GL_NO_ERROR,"GL to-VK import");to_vk_fd=-1;
    *out=im;return 1;
fail:
    if(memfd>=0)close(memfd);
    if(to_gl_fd>=0)close(to_gl_fd);
    if(to_vk_fd>=0)close(to_vk_fd);
    fs_interop_destroy(&im);return 0;
}
int fs_interop_gl_wait(const FsInteropImage *im) {
    if(!im||!im->texture||!im->gl_to_gl||!im->wait_semaphore)return 0;
    const GLenum layout=GL_LAYOUT_GENERAL_EXT;
    im->wait_semaphore(im->gl_to_gl,0,NULL,1,&im->texture,&layout);
    return glGetError()==GL_NO_ERROR;
}
int fs_interop_gl_signal(const FsInteropImage *im) {
    if(!im||!im->texture||!im->gl_to_vk||!im->signal_semaphore)return 0;
    const GLenum layout=GL_LAYOUT_GENERAL_EXT;
    im->signal_semaphore(im->gl_to_vk,0,NULL,1,&im->texture,&layout);
    return glGetError()==GL_NO_ERROR;
}
