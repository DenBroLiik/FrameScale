/* Linux JNI diagnostics. No GL calls, device creation, GPU submission or NGX. */
#include "by_xopilot_framescale_NativeBridge.h"
#include "../interop-probe/interop_image.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int device_extensions(VkPhysicalDevice gpu) {
    uint32_t count=0;
    if(vkEnumerateDeviceExtensionProperties(gpu,NULL,&count,NULL)!=VK_SUCCESS||count>4096)return 0;
    VkExtensionProperties *ext=calloc(count?count:1,sizeof(*ext));
    if(!ext)return 0;
    int mem=0,sem=0;
    if(vkEnumerateDeviceExtensionProperties(gpu,NULL,&count,ext)==VK_SUCCESS)
        for(uint32_t i=0;i<count;i++) {
            mem|=!strcmp(ext[i].extensionName,VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
            sem|=!strcmp(ext[i].extensionName,VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        }
    free(ext);return mem&&sem;
}

static int format_exportable(VkPhysicalDevice gpu,VkFormat format,VkImageUsageFlags usage) {
    VkPhysicalDeviceExternalImageFormatInfo external={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkPhysicalDeviceImageFormatInfo2 info={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .pNext=&external,.format=format,.type=VK_IMAGE_TYPE_2D,.tiling=VK_IMAGE_TILING_OPTIMAL,.usage=usage};
    VkExternalImageFormatProperties ep={.sType=VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 props={.sType=VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,.pNext=&ep};
    if(vkGetPhysicalDeviceImageFormatProperties2(gpu,&info,&props)!=VK_SUCCESS)return 0;
    return (ep.externalMemoryProperties.externalMemoryFeatures&VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)!=0
        &&(ep.externalMemoryProperties.compatibleHandleTypes&external.handleType)!=0;
}
static int semaphore_exportable(VkPhysicalDevice gpu) {
    VkPhysicalDeviceExternalSemaphoreInfo info={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkExternalSemaphoreProperties props={.sType=VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    vkGetPhysicalDeviceExternalSemaphoreProperties(gpu,&info,&props);
    return (props.externalSemaphoreFeatures&VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)!=0
        &&(props.compatibleHandleTypes&info.handleType)!=0;
}
static const char *yes(int v){return v?"yes":"no";}
JNIEXPORT jint JNICALL Java_by_xopilot_framescale_NativeBridge_nativeAbi(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;return 1;
}
JNIEXPORT jboolean JNICALL Java_by_xopilot_framescale_NativeBridge_nativeValidExtent(JNIEnv *env,jclass cls,jint w,jint h) {
    (void)env;(void)cls;
    return w>0&&h>0&&fs_interop_valid_extent((uint32_t)w,(uint32_t)h)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_by_xopilot_framescale_NativeBridge_nativeProbe(JNIEnv *env,jclass cls,jbyteArray ids,jbyteArray driver) {
    (void)cls;
    if(!ids||!driver)return (*env)->NewStringUTF(env,"Invalid UUID arrays");
    jsize length=(*env)->GetArrayLength(env,ids);
    if(length<16||length>256||length%16||(*env)->GetArrayLength(env,driver)!=16)
        return (*env)->NewStringUTF(env,"Invalid UUID arrays");
    jbyte devices[256],driver_id[16];
    (*env)->GetByteArrayRegion(env,ids,0,length,devices);
    if((*env)->ExceptionCheck(env))return NULL;
    (*env)->GetByteArrayRegion(env,driver,0,16,driver_id);
    if((*env)->ExceptionCheck(env))return NULL;
    char result[768]="No Vulkan device matches GL device and driver UUID";
    VkInstance instance=VK_NULL_HANDLE;
    VkPhysicalDevice *gpus=NULL,match=VK_NULL_HANDLE;
    VkPhysicalDeviceProperties selected={0};
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="FrameScale native diagnostic",.apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo info={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkResult vr=vkCreateInstance(&info,NULL,&instance);
    if(vr!=VK_SUCCESS){snprintf(result,sizeof(result),"Vulkan instance failed: %d",vr);goto done;}
    uint32_t n=0;vr=vkEnumeratePhysicalDevices(instance,&n,NULL);
    if(vr!=VK_SUCCESS||n==0||n>256){snprintf(result,sizeof(result),"Vulkan device enumeration unavailable: %d",vr);goto done;}
    gpus=calloc(n,sizeof(*gpus));
    if(!gpus){snprintf(result,sizeof(result),"Allocation failed");goto done;}
    vr=vkEnumeratePhysicalDevices(instance,&n,gpus);
    if(vr!=VK_SUCCESS){snprintf(result,sizeof(result),"Vulkan device enumeration failed: %d",vr);goto done;}
    for(uint32_t i=0;i<n;i++){
        VkPhysicalDeviceIDProperties uuid={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 p={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,.pNext=&uuid};
        vkGetPhysicalDeviceProperties2(gpus[i],&p);
        if(p.properties.apiVersion<VK_API_VERSION_1_1||memcmp(uuid.driverUUID,driver_id,16))continue;
        for(jsize j=0;j<length;j+=16) {
            if(memcmp(uuid.deviceUUID,devices+j,16))continue;
            if(match&&match!=gpus[i]){snprintf(result,sizeof(result),"Ambiguous GL/Vulkan device match");goto done;}
            match=gpus[i];selected=p.properties;break;
        }
    }
    if(match){
        VkImageUsageFlags input=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
        VkImageUsageFlags output=input|VK_IMAGE_USAGE_STORAGE_BIT;
        snprintf(result,sizeof(result),
            "Matched Vulkan device vendor=0x%04x device=0x%04x API=%u.%u | FD extensions=%s semaphore export=%s | RGBA8=%s HDR RGBA16F input=%s HDR RGBA16F storage output=%s R32F depth-copy=%s RG16F motion=%s | capability query only; no interop/NGX execution",
            selected.vendorID,selected.deviceID,VK_API_VERSION_MAJOR(selected.apiVersion),VK_API_VERSION_MINOR(selected.apiVersion),
            yes(device_extensions(match)),yes(semaphore_exportable(match)),
            yes(format_exportable(match,VK_FORMAT_R8G8B8A8_UNORM,input)),
            yes(format_exportable(match,VK_FORMAT_R16G16B16A16_SFLOAT,input)),
            yes(format_exportable(match,VK_FORMAT_R16G16B16A16_SFLOAT,output)),
            yes(format_exportable(match,VK_FORMAT_R32_SFLOAT,input)),
            yes(format_exportable(match,VK_FORMAT_R16G16_SFLOAT,input)));
    }
done:
    free(gpus);if(instance)vkDestroyInstance(instance,NULL);
    return (*env)->NewStringUTF(env,result);
}
