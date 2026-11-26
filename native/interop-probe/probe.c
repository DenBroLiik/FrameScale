/* Standalone Linux Vulkan -> OpenGL external image smoke test.
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

#define W 8
#define H 8
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
    int rc=1,memfd=-1,semfd=-1,submitted=0;
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;
    VkInstance instance=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
    VkPhysicalDevice gpu=VK_NULL_HANDLE,*gpus=NULL;
    VkImage image=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;
    VkSemaphore semaphore=VK_NULL_HANDLE;VkCommandPool pool=VK_NULL_HANDLE;
    VkFence fence=VK_NULL_HANDLE;VkQueue queue=VK_NULL_HANDLE;VkCommandBuffer cmd=VK_NULL_HANDLE;
    GLuint glmem=0,glsem=0,texture=0;
    PFNGLDELETEMEMORYOBJECTSEXTPROC delete_mem=NULL;
    PFNGLDELETESEMAPHORESEXTPROC delete_sem=NULL;
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
    PROC(PFNGLCREATEMEMORYOBJECTSEXTPROC,create_mem,"glCreateMemoryObjectsEXT");
    PROC(PFNGLMEMORYOBJECTPARAMETERIVEXTPROC,mem_param,"glMemoryObjectParameterivEXT");
    PROC(PFNGLIMPORTMEMORYFDEXTPROC,import_mem,"glImportMemoryFdEXT");
    PROC(PFNGLTEXTURESTORAGEMEM2DEXTPROC,storage,"glTextureStorageMem2DEXT");
    PROC(PFNGLGENSEMAPHORESEXTPROC,gen_sem,"glGenSemaphoresEXT");
    PROC(PFNGLIMPORTSEMAPHOREFDEXTPROC,import_sem,"glImportSemaphoreFdEXT");
    PROC(PFNGLWAITSEMAPHOREEXTPROC,wait_sem,"glWaitSemaphoreEXT");
    delete_mem=(PFNGLDELETEMEMORYOBJECTSEXTPROC)eglGetProcAddress("glDeleteMemoryObjectsEXT");
    delete_sem=(PFNGLDELETESEMAPHORESEXTPROC)eglGetProcAddress("glDeleteSemaphoresEXT");
    CHECK(delete_mem&&delete_sem,"Missing cleanup entrypoints");
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
    PFN_vkGetMemoryFdKHR get_mfd=(PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device,"vkGetMemoryFdKHR");
    PFN_vkGetSemaphoreFdKHR get_sfd=(PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(device,"vkGetSemaphoreFdKHR");
    CHECK(get_mfd&&get_sfd,"Missing Vulkan FD functions");
    VkExternalMemoryImageCreateInfo ei={.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,.handleTypes=ext.handleType};
    VkImageCreateInfo ii={.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,.pNext=&ei,.imageType=fi.type,.format=fi.format,
        .extent={W,H,1},.mipLevels=1,.arrayLayers=1,.samples=VK_SAMPLE_COUNT_1_BIT,.tiling=fi.tiling,
        .usage=fi.usage,.sharingMode=VK_SHARING_MODE_EXCLUSIVE,.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED};
    VK(vkCreateImage(device,&ii,NULL,&image));
    VkMemoryRequirements req;vkGetImageMemoryRequirements(device,image,&req);
    VkPhysicalDeviceMemoryProperties mp;vkGetPhysicalDeviceMemoryProperties(gpu,&mp);
    uint32_t mt=UINT32_MAX;
    for(uint32_t j=0;j<mp.memoryTypeCount;j++)if(req.memoryTypeBits&(1u<<j)){mt=j;if(mp.memoryTypes[j].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)break;}
    CHECK(mt!=UINT32_MAX,"No compatible memory type");
    VkMemoryDedicatedAllocateInfo dedicated={.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,.image=image};
    VkExportMemoryAllocateInfo em={.sType=VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,.pNext=&dedicated,.handleTypes=ext.handleType};
    VkMemoryAllocateInfo ma={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.pNext=&em,.allocationSize=req.size,.memoryTypeIndex=mt};
    VK(vkAllocateMemory(device,&ma,NULL,&memory));VK(vkBindImageMemory(device,image,memory,0));
    VkMemoryGetFdInfoKHR mf={.sType=VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR,.memory=memory,.handleType=ext.handleType};
    VK(get_mfd(device,&mf,&memfd));CHECK(memfd>=0,"Invalid memory FD");
    VkExportSemaphoreCreateInfo es={.sType=VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,.handleTypes=si.handleType};
    VkSemaphoreCreateInfo sc={.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,.pNext=&es};
    VK(vkCreateSemaphore(device,&sc,NULL,&semaphore));
    VkSemaphoreGetFdInfoKHR sf={.sType=VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,.semaphore=semaphore,.handleType=si.handleType};
    VK(get_sfd(device,&sf,&semfd));CHECK(semfd>=0,"Invalid semaphore FD");
    create_mem(1,&glmem);CHECK(glmem&&glGetError()==GL_NO_ERROR,"GL memory object failed");
    GLint yes=GL_TRUE;mem_param(glmem,GL_DEDICATED_MEMORY_OBJECT_EXT,&yes);
    CHECK(glGetError()==GL_NO_ERROR,"GL dedicated flag failed");
    import_mem(glmem,req.size,GL_HANDLE_TYPE_OPAQUE_FD_EXT,memfd);
    CHECK(glGetError()==GL_NO_ERROR,"GL memory import failed");memfd=-1; /* GL owns imported FD. */
    glCreateTextures(GL_TEXTURE_2D,1,&texture);CHECK(texture,"GL texture allocation failed");
    glTextureParameteri(texture,GL_TEXTURE_TILING_EXT,GL_OPTIMAL_TILING_EXT);
    storage(texture,1,GL_RGBA8,W,H,glmem,0);CHECK(glGetError()==GL_NO_ERROR,"GL imported image storage failed");
    gen_sem(1,&glsem);CHECK(glsem&&glGetError()==GL_NO_ERROR,"GL semaphore allocation failed");
    import_sem(glsem,GL_HANDLE_TYPE_OPAQUE_FD_EXT,semfd);
    CHECK(glGetError()==GL_NO_ERROR,"GL semaphore import failed");semfd=-1;
    VkCommandPoolCreateInfo pc={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,.queueFamilyIndex=family};
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
    /* Bounded diagnostic wait, not a production render path. External semaphore is still consumed by GL. */
    VK(vkWaitForFences(device,1,&fence,VK_TRUE,5ull*1000000000ull));submitted=0;
    GLenum layout=GL_LAYOUT_GENERAL_EXT;wait_sem(glsem,0,NULL,1,&texture,&layout);
    CHECK(glGetError()==GL_NO_ERROR,"GL external semaphore wait failed");
    uint8_t pixels[W*H*4];glGetTextureImage(texture,0,GL_RGBA,GL_UNSIGNED_BYTE,sizeof(pixels),pixels);
    CHECK(glGetError()==GL_NO_ERROR,"GL imported texture readback failed");
    for(int j=0;j<W*H;j++) CHECK(pixels[j*4]==0&&pixels[j*4+1]==255&&pixels[j*4+2]==0&&pixels[j*4+3]==255,"Shared image pixel mismatch");
    puts("PASS: same-device Vulkan -> OpenGL opaque FD image + semaphore, 64 pixels verified");rc=0;
cleanup:
    /* If GPU work timed out, do not destroy in-flight resources. The standalone process exits. */
    if(submitted){fprintf(stderr,"GPU work incomplete; exiting probe without explicit destruction\n");fflush(NULL);_Exit(1);}
    if(context!=EGL_NO_CONTEXT&&eglGetCurrentContext()==context) {
        if(texture)glDeleteTextures(1,&texture);
        if(glsem&&delete_sem)delete_sem(1,&glsem);
        if(glmem&&delete_mem)delete_mem(1,&glmem);
        glFinish();eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    }
    if(memfd>=0)close(memfd);
    if(semfd>=0)close(semfd);
    if(device){
        if(fence)vkDestroyFence(device,fence,NULL);
        if(pool)vkDestroyCommandPool(device,pool,NULL);
        if(semaphore)vkDestroySemaphore(device,semaphore,NULL);
        if(image)vkDestroyImage(device,image,NULL);
        if(memory)vkFreeMemory(device,memory,NULL);
        vkDestroyDevice(device,NULL);
    }
    free(gpus);if(instance)vkDestroyInstance(instance,NULL);
    if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
    if(display!=EGL_NO_DISPLAY)eglTerminate(display);
    return rc;
}
