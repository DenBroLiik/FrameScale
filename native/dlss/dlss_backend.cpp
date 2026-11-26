#include "dlss_backend.hpp"
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <array>
#include <atomic>
#include <cstdio>

namespace fs {
namespace {
std::atomic<bool> occupied{false};
bool resource(NVSDK_NGX_Resource_VK& r,Extent e,VkFormat fmt,bool write) {
    if(r.Type!=NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW||r.ReadWrite!=write)return false;
    const auto& v=r.Resource.ImageViewInfo;
    const auto& range=v.SubresourceRange;
    return v.Image&&v.ImageView&&v.Width==e.w&&v.Height==e.h&&v.Format==fmt
        &&range.aspectMask==VK_IMAGE_ASPECT_COLOR_BIT&&range.baseMipLevel==0&&range.levelCount==1
        &&range.baseArrayLayer==0&&range.layerCount==1;
}
}
bool DlssBackend::fail(const char* msg){error_=msg;return false;}
bool DlssBackend::check(NVSDK_NGX_Result r,const char* stage){
    if(NVSDK_NGX_SUCCEED(r))return true;
    char message[160];std::snprintf(message,sizeof(message),"%s: NGX 0x%08x",stage,unsigned(r));
    error_=message;return false;
}
bool DlssBackend::open(VkInstance instance,VkPhysicalDevice gpu,VkDevice device,
    const std::string& projectId,const std::wstring& dataPath,const std::wstring& sdkPath) {
    if(device_||!instance||!gpu||!device||projectId.empty()||dataPath.empty()||sdkPath.empty())
        return fail("Invalid or already open NGX session");
    VkPhysicalDeviceProperties properties{};vkGetPhysicalDeviceProperties(gpu,&properties);
    if(properties.vendorID!=0x10de)return fail("NVIDIA physical device required");
    project_=projectId;data_=dataPath;sdk_=sdkPath;
    if(occupied.exchange(true))return fail("Only one FrameScale NGX session allowed");
    owner_=std::this_thread::get_id();device_=device;
    search_[0]=sdk_.c_str();common_.PathListInfo.Path=search_;common_.PathListInfo.Length=1;
    auto result=NVSDK_NGX_VULKAN_Init_with_ProjectID(project_.c_str(),NVSDK_NGX_ENGINE_TYPE_CUSTOM,
        "FrameScale-0.7",data_.c_str(),instance,gpu,device_,vkGetInstanceProcAddr,vkGetDeviceProcAddr,&common_,NVSDK_NGX_Version_API);
    if(!check(result,"NGX init")){
        device_=VK_NULL_HANDLE;occupied.store(false);return false;
    }
    init_=true;
    if(!check(NVSDK_NGX_VULKAN_GetCapabilityParameters(&params_),"Capability parameters")||!params_){
        if(!params_&&NVSDK_NGX_SUCCEED(result)&&error_.empty())error_="No capability parameters";
        auto original=error_;if(close())error_=original;return false;
    }
    int available=0,updated=0;
    if(!check(NVSDK_NGX_Parameter_GetI(params_,NVSDK_NGX_Parameter_SuperSampling_Available,&available),"DLSS availability")
        ||!available){
        if(!available&&error_.empty())error_="DLSS Super Resolution unavailable";
        auto original=error_;if(close())error_=original;return false;
    }
    if(NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(params_,NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver,&updated))&&updated){
        auto original=std::string("NVIDIA driver update required");if(close())error_=original;return false;
    }
    usable_=true;error_.clear();return true;
}
bool DlssBackend::optimal(Extent output,NVSDK_NGX_PerfQuality_Value quality,Optimal& out) {
    if(!owner()||!usable_||!params_||handle_||!valid(output))return fail("Invalid optimal-settings request");
    Optimal candidate{};candidate.output=output;float sharp=0;
    auto r=NGX_DLSS_GET_OPTIMAL_SETTINGS(params_,output.w,output.h,quality,
        &candidate.recommended.w,&candidate.recommended.h,&candidate.maximum.w,&candidate.maximum.h,
        &candidate.minimum.w,&candidate.minimum.h,&sharp);
    if(!check(r,"DLSS optimal settings"))return false;
    if(!candidate.valid())return fail("NGX returned unsupported dimensions");
    out=candidate;error_.clear();return true;
}
bool DlssBackend::create(VkCommandBuffer cmd,const Optimal& size,NVSDK_NGX_PerfQuality_Value quality) {
    if(!owner()||!usable_||!params_||handle_||!cmd||!size.valid())return fail("Invalid DLSS creation request");
    // Requery the actual SDK; this first backend supports a fixed recommended extent only.
    Optimal actual{};
    if(!optimal(size.output,quality,actual))return false;
    if(!(actual.recommended==size.recommended))return fail("Render extent differs from SDK recommendation");
    NVSDK_NGX_DLSS_Create_Params c{};
    c.Feature.InWidth=actual.recommended.w;c.Feature.InHeight=actual.recommended.h;
    c.Feature.InTargetWidth=actual.output.w;c.Feature.InTargetHeight=actual.output.h;
    c.Feature.InPerfQualityValue=quality;
    c.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR|NVSDK_NGX_DLSS_Feature_Flags_MVLowRes
        |NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    if(!check(NGX_VULKAN_CREATE_DLSS_EXT1(device_,cmd,1,1,&handle_,params_,&c),"DLSS create")){usable_=false;return false;}
    if(!handle_){usable_=false;return fail("NGX returned no feature handle");}
    size_=actual;first_=true;error_.clear();return true;
}
bool DlssBackend::evaluate(VkCommandBuffer cmd,NVSDK_NGX_Resource_VK& color,NVSDK_NGX_Resource_VK& depth,
    NVSDK_NGX_Resource_VK& motion,NVSDK_NGX_Resource_VK& output,const FrameInfo& frame) {
    if(!owner()||!usable_||!handle_||!cmd||!frame.valid())return fail("Invalid DLSS evaluation state");
    auto src=size_.recommended;
    if(!resource(color,src,VK_FORMAT_R16G16B16A16_SFLOAT,false)
        ||!resource(depth,src,VK_FORMAT_R32_SFLOAT,false)
        ||!resource(motion,src,VK_FORMAT_R16G16_SFLOAT,false)
        ||!resource(output,size_.output,VK_FORMAT_R16G16B16A16_SFLOAT,true))
        return fail("Invalid DLSS resource shape or format");
    std::array<VkImage,4> images={color.Resource.ImageViewInfo.Image,depth.Resource.ImageViewInfo.Image,
        motion.Resource.ImageViewInfo.Image,output.Resource.ImageViewInfo.Image};
    for(unsigned i=0;i<images.size();i++)for(unsigned j=i+1;j<images.size();j++)
        if(images[i]==images[j])return fail("DLSS images must not alias");
    NVSDK_NGX_VK_DLSS_Eval_Params e{};
    e.Feature.pInColor=&color;e.Feature.pInOutput=&output;e.pInDepth=&depth;e.pInMotionVectors=&motion;
    e.InJitterOffsetX=frame.jitterX;e.InJitterOffsetY=frame.jitterY;
    e.InRenderSubrectDimensions={src.w,src.h};
    e.InReset=first_||frame.reset;e.InMVScaleX=1;e.InMVScaleY=1;
    e.InPreExposure=frame.preExposure;e.InExposureScale=1;e.InFrameTimeDeltaInMsec=frame.frameMs;
    if(!check(NGX_VULKAN_EVALUATE_DLSS_EXT(cmd,handle_,params_,&e),"DLSS evaluate")){
        usable_=false;return false;
    }
    first_=false;error_.clear();return true;
}
bool DlssBackend::close() {
    if(!device_)return true;
    if(!owner())return fail("NGX close on wrong thread");
    usable_=false;
    if(handle_){
        if(!check(NVSDK_NGX_VULKAN_ReleaseFeature(handle_),"Release DLSS"))return false;
        handle_=nullptr;
    }
    if(params_){
        if(!check(NVSDK_NGX_VULKAN_DestroyParameters(params_),"Destroy parameters"))return false;
        params_=nullptr;
    }
    if(init_&&!check(NVSDK_NGX_VULKAN_Shutdown1(device_),"NGX shutdown"))return false;
    init_=false;device_=VK_NULL_HANDLE;occupied.store(false);return true;
}
}
