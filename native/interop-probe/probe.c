/* Standalone Linux Vulkan <-> OpenGL external image roundtrip test.
 * No game resources, no NVIDIA SDK, no persistent state. */
#define GL_GLEXT_PROTOTYPES 1
#include <EGL/egl.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "interop_image.h"

#define W 8
#define H 8
#define ROUNDS 16
#define PIXEL_BYTES (W*H*4)
#define CHECK(c,msg) do {if(!(c)){fprintf(stderr,"%s\n",msg);goto cleanup;}}while(0)
#define VK(call) do {VkResult e=(call);if(e!=VK_SUCCESS){fprintf(stderr,"%s: %d\n",#call,e);goto cleanup;}}while(0)
#define PROC(type,var,name) type var=(type)eglGetProcAddress(name); CHECK(var,"Missing " name)
static void print_uuid(const uint8_t *p) {for(int i=0;i<16;i++)printf("%02x",p[i]);}
static int matches(const uint8_t *p,const char *hex) {
    char text[33];for(int i=0;i<16;i++)snprintf(text+2*i,3,"%02x",p[i]);
    return strcmp(text,hex)==0;
}
static int extension(const char *name) {
    GLint count=0;glGetIntegerv(GL_NUM_EXTENSIONS,&count);
    for(GLint i=0;i<count;i++) {
        const char *s=(const char*)glGetStringi(GL_EXTENSIONS,(GLuint)i);
        if(s&&strcmp(s,name)==0)return 1;
    }
    return 0;
}
static int device_exts(VkPhysicalDevice gpu) {
    uint32_t n=0;
    if(vkEnumerateDeviceExtensionProperties(gpu,NULL,&n,NULL)!=VK_SUCCESS||n>4096)return 0;
    VkExtensionProperties *p=calloc(n?n:1,sizeof(*p));if(!p)return 0;
    int mem=0,sem=0;
    if(vkEnumerateDeviceExtensionProperties(gpu,NULL,&n,p)==VK_SUCCESS)
        for(uint32_t i=0;i<n;i++) {
            mem|=strcmp(p[i].extensionName,VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)==0;
            sem|=strcmp(p[i].extensionName,VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)==0;
        }
    free(p);return mem&&sem;
}
int main(int argc,char **argv) {
    if(argc>2||(argc==2&&strlen(argv[1])!=32)) {
        fprintf(stderr,"Usage: %s [32-character lowercase Minecraft GL UUID]\n",argv[0]);return 2;
    }
    int rc=1,submitted=0,gl_pending=0;
    FsInteropImage shared={0};
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;
    VkInstance instance=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
    VkPhysicalDevice gpu=VK_NULL_HANDLE,*gpus=NULL;
    VkImage image=VK_NULL_HANDLE;
    VkSemaphore semaphore=VK_NULL_HANDLE,returned=VK_NULL_HANDLE;
    VkCommandPool pool=VK_NULL_HANDLE;
    VkBuffer readback=VK_NULL_HANDLE;VkDeviceMemory host=VK_NULL_HANDLE;
    void *mapped=NULL;
    VkFence fence=VK_NULL_HANDLE;VkQueue queue=VK_NULL_HANDLE;VkCommandBuffer cmd=VK_NULL_HANDLE;
    GLuint texture=0;
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if(display==EGL_NO_DISPLAY||!eglInitialize(display,NULL,NULL)||!eglBindAPI(EGL_OPENGL_API)) {
        fprintf(stderr,"SKIP: no EGL OpenGL display\n");rc=77;goto cleanup;
    }
    EGLConfig cfg;EGLint count=0;
    const EGLint conf[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_BIT,EGL_NONE};
    const EGLint ca[]={EGL_CONTEXT_MAJOR_VERSION,4,EGL_CONTEXT_MINOR_VERSION,5,
        EGL_CONTEXT_OPENGL_PROFILE_MASK,EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,EGL_NONE};
    if(!eglChooseConfig(display,conf,&cfg,1,&count)||count!=1||
        (context=eglCreateContext(display,cfg,EGL_NO_CONTEXT,ca))==EGL_NO_CONTEXT||
        !eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,context)) {
        fprintf(stderr,"SKIP: no surfaceless OpenGL 4.5 context\n");rc=77;goto cleanup;
    }
    printf("GL renderer: %s\n",glGetString(GL_RENDERER));
    const char *required[]={"GL_EXT_memory_object","GL_EXT_memory_object_fd",
        "GL_EXT_semaphore","GL_EXT_semaphore_fd"};
    for(unsigned i=0;i<sizeof(required)/sizeof(required[0]);i++)if(!extension(required[i])) {
        fprintf(stderr,"SKIP: missing %s\n",required[i]);rc=77;goto cleanup;
    }
    PROC(PFNGLGETUNSIGNEDBYTEI_VEXTPROC,get_uuid,"glGetUnsignedBytei_vEXT");
    PROC(PFNGLGETUNSIGNEDBYTEVEXTPROC,get_driver,"glGetUnsignedBytevEXT");
    GLint uuid_count=0;glGetIntegerv(GL_NUM_DEVICE_UUIDS_EXT,&uuid_count);
    CHECK(uuid_count>=1&&uuid_count<=16,"Invalid GL device UUID count");
    uint8_t uuids[16][16],driver[16];get_driver(GL_DRIVER_UUID_EXT,driver);
    for(GLint i=0;i<uuid_count;i++){get_uuid(GL_DEVICE_UUID_EXT,(GLuint)i,uuids[i]);printf("GL UUID: ");print_uuid(uuids[i]);puts("");}
    CHECK(glGetError()==GL_NO_ERROR,"GL UUID query failed");
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="FrameScale interop probe",.apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo ic={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VK(vkCreateInstance(&ic,NULL,&instance));
    uint32_t n=0;VK(vkEnumeratePhysicalDevices(instance,&n,NULL));CHECK(n>0&&n<=256,"No suitable Vulkan devices");
    gpus=calloc(n,sizeof(*gpus));CHECK(gpus,"GPU allocation failed");VK(vkEnumeratePhysicalDevices(instance,&n,gpus));
    for(uint32_t i=0;i<n;i++) {
        VkPhysicalDeviceIDProperties id={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 prop={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,.pNext=&id};
        vkGetPhysicalDeviceProperties2(gpus[i],&prop);
        printf("VK device: %s UUID: ",prop.properties.deviceName);print_uuid(id.deviceUUID);puts("");
        for(GLint j=0;j<uuid_count;j++)
            if(!memcmp(id.deviceUUID,uuids[j],16)&&!memcmp(id.driverUUID,driver,16)
                &&(argc==1||matches(id.deviceUUID,argv[1]))&&device_exts(gpus[i])) {
                CHECK(gpu==VK_NULL_HANDLE,"Ambiguous GL/Vulkan match");gpu=gpus[i];
            }
    }
    if(!gpu){fprintf(stderr,"SKIP: no matching GL/VK device+driver UUID with FD extensions\n");rc=77;goto cleanup;}
    VkPhysicalDeviceExternalImageFormatInfo ext={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkPhysicalDeviceImageFormatInfo2 fi={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,.pNext=&ext,
        .format=VK_FORMAT_R8G8B8A8_UNORM,.type=VK_IMAGE_TYPE_2D,.tiling=VK_IMAGE_TILING_OPTIMAL,
        .usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_SAMPLED_BIT};
    VkExternalImageFormatProperties xp={.sType=VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 fp={.sType=VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,.pNext=&xp};
    VkPhysicalDeviceExternalSemaphoreInfo si={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkExternalSemaphoreProperties sp={.sType=VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    vkGetPhysicalDeviceExternalSemaphoreProperties(gpu,&si,&sp);
    if(vkGetPhysicalDeviceImageFormatProperties2(gpu,&fi,&fp)!=VK_SUCCESS||
       !(xp.externalMemoryProperties.externalMemoryFeatures&VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)||
       !(xp.externalMemoryProperties.compatibleHandleTypes&ext.handleType)||
       !(sp.externalSemaphoreFeatures&VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)||
       !(sp.compatibleHandleTypes&si.handleType)) {
        fprintf(stderr,"SKIP: RGBA8 opaque FD image or binary semaphore export unsupported\n");rc=77;goto cleanup;
    }
    uint32_t nq=0;vkGetPhysicalDeviceQueueFamilyProperties(gpu,&nq,NULL);
    CHECK(nq>0&&nq<=256,"No queue families");
    VkQueueFamilyProperties qs[256];vkGetPhysicalDeviceQueueFamilyProperties(gpu,&nq,qs);
    uint32_t family=UINT32_MAX;
    for(uint32_t j=0;j<nq;j++)if(qs[j].queueCount&&(qs[j].queueFlags&VK_QUEUE_GRAPHICS_BIT)){family=j;break;}
    CHECK(family!=UINT32_MAX,"No graphics queue");
    float priority=1;
    VkDeviceQueueCreateInfo qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    const char *exts[]={VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME};
    VkDeviceCreateInfo dc={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qi,.enabledExtensionCount=2,.ppEnabledExtensionNames=exts};
    VK(vkCreateDevice(gpu,&dc,NULL,&device));vkGetDeviceQueue(device,family,0,&queue);
    CHECK(fs_interop_create(&shared,gpu,device,W,H),"Shared image creation failed");
    image=shared.image;texture=shared.texture;
    semaphore=shared.to_gl;returned=shared.to_vk;
    VkPhysicalDeviceMemoryProperties mp;vkGetPhysicalDeviceMemoryProperties(gpu,&mp);

    /* Reused verification buffer, separate from the shared image. Host readback is
       diagnostic only; there is no CPU copy between the graphics APIs. */
    VkBufferCreateInfo bi={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=PIXEL_BYTES,
        .usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    VK(vkCreateBuffer(device,&bi,NULL,&readback));
    VkMemoryRequirements br;vkGetBufferMemoryRequirements(device,readback,&br);
    uint32_t hm=UINT32_MAX;
    for(uint32_t j=0;j<mp.memoryTypeCount;j++) {
        VkMemoryPropertyFlags flags=mp.memoryTypes[j].propertyFlags;
        if((br.memoryTypeBits&(1u<<j))&&(flags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
            hm=j;if(flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)break;
        }
    }
    CHECK(hm!=UINT32_MAX,"No host-visible verification memory");
    VkMemoryAllocateInfo ha={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=br.size,.memoryTypeIndex=hm};
    VK(vkAllocateMemory(device,&ha,NULL,&host));VK(vkBindBufferMemory(device,readback,host,0));
    VK(vkMapMemory(device,host,0,VK_WHOLE_SIZE,0,&mapped));
    int coherent=(mp.memoryTypes[hm].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
    VkCommandPoolCreateInfo pc={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,.queueFamilyIndex=family};
    VK(vkCreateCommandPool(device,&pc,NULL,&pool));
    VkCommandBufferAllocateInfo ac={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    VK(vkAllocateCommandBuffers(device,&ac,&cmd));
    VkCommandBufferBeginInfo bc={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    VK(vkBeginCommandBuffer(cmd,&bc));
    VkImageSubresourceRange range={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    VkImageMemoryBarrier barrier={.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout=VK_IMAGE_LAYOUT_UNDEFINED,.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.image=image,.subresourceRange=range};
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,NULL,0,NULL,1,&barrier);
    VkClearColorValue color={.float32={0.0f,1.0f,0.0f,1.0f}};
    vkCmdClearColorImage(cmd,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&color,1,&range);
    barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=0;
    barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex=family;barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,NULL,0,NULL,1,&barrier);
    VK(vkEndCommandBuffer(cmd));
    VkFenceCreateInfo fc={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VK(vkCreateFence(device,&fc,NULL,&fence));
    VkSubmitInfo sub={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&cmd,.signalSemaphoreCount=1,.pSignalSemaphores=&semaphore};
    VK(vkQueueSubmit(queue,1,&sub,fence));submitted=1;
    /* Fence waits and readbacks verify the transport, not production frame pacing.
       Each binary semaphore has one signal and one wait before reuse. */
    VK(vkWaitForFences(device,1,&fence,VK_TRUE,5ull*1000000000ull));submitted=0;
    uint8_t pixels[PIXEL_BYTES];
    uint8_t expected[4]={0,255,0,255};
    VkPipelineStageFlags wait_stage=VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    for(unsigned round=0;round<ROUNDS;round++) {
        gl_pending=1;
        CHECK(fs_interop_gl_wait(&shared),"GL external semaphore wait failed");
        glGetTextureImage(texture,0,GL_RGBA,GL_UNSIGNED_BYTE,sizeof(pixels),pixels);
        CHECK(glGetError()==GL_NO_ERROR,"GL imported texture readback failed");
        for(int j=0;j<W*H;j++)
            CHECK(memcmp(pixels+4*j,expected,4)==0,"Vulkan -> GL pixel mismatch");

        /* Distinct content every round catches stale data and missing ownership transfer. */
        const uint8_t produced[4]={(uint8_t)(11+round*7),(uint8_t)(239-round*5),
            (uint8_t)(33+round*3),255};
        glClearTexImage(texture,0,GL_RGBA,GL_UNSIGNED_BYTE,produced);
        CHECK(glGetError()==GL_NO_ERROR,"GL shared texture clear failed");
        CHECK(fs_interop_gl_signal(&shared),"GL return semaphore signal failed");
        glFlush();

        VK(vkResetFences(device,1,&fence));
        VK(vkResetCommandBuffer(cmd,0));
        VK(vkBeginCommandBuffer(cmd,&bc));
        /* Acquire external ownership. GENERAL is the layout signaled by GL. */
        barrier.srcAccessMask=0;barrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
        barrier.oldLayout=VK_IMAGE_LAYOUT_GENERAL;barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        barrier.srcQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;barrier.dstQueueFamilyIndex=family;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,NULL,0,NULL,1,&barrier);
        VkBufferImageCopy region={.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},.imageExtent={W,H,1}};
        vkCmdCopyImageToBuffer(cmd,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback,1,&region);
        VkBufferMemoryBarrier host_barrier={.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT,
            .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
            .buffer=readback,.offset=0,.size=VK_WHOLE_SIZE};
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,NULL,1,&host_barrier,0,NULL);
        if(round+1<ROUNDS) {
            barrier.srcAccessMask=VK_ACCESS_TRANSFER_READ_BIT;barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,NULL,0,NULL,1,&barrier);
            expected[0]=(uint8_t)(round*9+1);expected[1]=(uint8_t)(round*3+2);expected[2]=(uint8_t)(255-round*7);
            VkClearColorValue next_color={.float32={expected[0]/255.0f,expected[1]/255.0f,expected[2]/255.0f,1.0f}};
            vkCmdClearColorImage(cmd,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,&next_color,1,&range);
            barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=0;
            barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_GENERAL;
            barrier.srcQueueFamilyIndex=family;barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_EXTERNAL;
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,NULL,0,NULL,1,&barrier);
        }
        /* On the last iteration VK keeps ownership and does not signal an unused semaphore. */
        VK(vkEndCommandBuffer(cmd));
        sub.waitSemaphoreCount=1;sub.pWaitSemaphores=&returned;sub.pWaitDstStageMask=&wait_stage;
        sub.signalSemaphoreCount=round+1<ROUNDS?1:0;
        sub.pSignalSemaphores=sub.signalSemaphoreCount?&semaphore:NULL;
        VK(vkQueueSubmit(queue,1,&sub,fence));submitted=1;
        VK(vkWaitForFences(device,1,&fence,VK_TRUE,5ull*1000000000ull));submitted=0;gl_pending=0;
        if(!coherent) {
            VkMappedMemoryRange invalidate={.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,.memory=host,.offset=0,.size=VK_WHOLE_SIZE};
            VK(vkInvalidateMappedMemoryRanges(device,1,&invalidate));
        }
        for(int j=0;j<W*H;j++)
            CHECK(memcmp((const uint8_t*)mapped+4*j,produced,4)==0,"GL -> Vulkan pixel mismatch");
    }
    printf("PASS: %u bidirectional rounds, %u pixels verified in each direction; one shared image, two reused semaphores\n",
        (unsigned)ROUNDS,(unsigned)(ROUNDS*W*H));rc=0;
cleanup:
    /* If GPU work timed out, do not destroy in-flight resources. The standalone process exits. */
    if(submitted||gl_pending){fprintf(stderr,"GPU work incomplete; exiting probe without explicit destruction\n");fflush(NULL);_Exit(1);}
    if(context!=EGL_NO_CONTEXT&&eglGetCurrentContext()==context) {
        glFinish();
        fs_interop_destroy(&shared);
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    }
    if(device){
        if(fence)vkDestroyFence(device,fence,NULL);
        if(pool)vkDestroyCommandPool(device,pool,NULL);
        if(mapped)vkUnmapMemory(device,host);
        if(readback)vkDestroyBuffer(device,readback,NULL);
        if(host)vkFreeMemory(device,host,NULL);
        vkDestroyDevice(device,NULL);
    }
    free(gpus);if(instance)vkDestroyInstance(instance,NULL);
    if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
    if(display!=EGL_NO_DISPLAY)eglTerminate(display);
    return rc;
}
