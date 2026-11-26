// ABI translation only: lifecycle policy, validation and scheduling live in Rust.
#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <cstdint>
#include <string>
#include <memory>
#include <type_traits>

struct Extent {uint32_t w,h;};
struct Optimal {Extent recommended,minimum,maximum,output;};
struct Frame {float jitter_x,jitter_y,pre_exposure,frame_ms;uint32_t reset;};
struct Image {uint64_t image,view;Extent extent;uint32_t format,writable;};
struct Images {Image color,depth,motion,output;};
static_assert(sizeof(Extent)==8&&sizeof(Optimal)==32&&sizeof(Frame)==20&&sizeof(Image)==32&&sizeof(Images)==128);
static_assert(sizeof(wchar_t)==4);
struct Context {
    VkDevice device{};
    NVSDK_NGX_Parameter* params{};
    NVSDK_NGX_Handle* feature{};
    std::string project;
    std::wstring data,sdk;
    const wchar_t* search[1]{};
    NVSDK_NGX_FeatureCommonInfo common{};
    bool initialized=false;
};
template<typename T> T handle(uint64_t n){
    if constexpr(std::is_pointer_v<T>)return reinterpret_cast<T>(static_cast<uintptr_t>(n));
    else return static_cast<T>(n);
}
static uint32_t result(NVSDK_NGX_Result r){return NVSDK_NGX_SUCCEED(r)?0:static_cast<uint32_t>(r);}
static constexpr uint32_t invalid=0xffffffffu;
static uint32_t close_context(Context* c){
    uint32_t r;
    if(c->feature){r=result(NVSDK_NGX_VULKAN_ReleaseFeature(c->feature));if(r)return r;c->feature=nullptr;}
    if(c->params){r=result(NVSDK_NGX_VULKAN_DestroyParameters(c->params));if(r)return r;c->params=nullptr;}
    if(c->initialized){r=result(NVSDK_NGX_VULKAN_Shutdown1(c->device));if(r)return r;c->initialized=false;}
    delete c;return 0;
}
extern "C" uint32_t fs_ngx_open(uint64_t instance,uint64_t gpu,uint64_t device,const char* project,
    const int32_t* data,const int32_t* sdk,void** out) noexcept {
    if(!out)return invalid;
    *out=nullptr;
    try{
        auto c=std::make_unique<Context>();
        c->device=handle<VkDevice>(device);c->project=project;
        for(;*data;++data)c->data.push_back(static_cast<wchar_t>(*data));
        for(;*sdk;++sdk)c->sdk.push_back(static_cast<wchar_t>(*sdk));
        c->search[0]=c->sdk.c_str();c->common.PathListInfo.Path=c->search;c->common.PathListInfo.Length=1;
        VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(handle<VkPhysicalDevice>(gpu),&p);
        if(p.vendorID!=0x10de)return invalid;
        auto r=result(NVSDK_NGX_VULKAN_Init_with_ProjectID(c->project.c_str(),NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            "FrameScale-0.7",c->data.c_str(),handle<VkInstance>(instance),handle<VkPhysicalDevice>(gpu),c->device,
            vkGetInstanceProcAddr,vkGetDeviceProcAddr,&c->common,NVSDK_NGX_Version_API));
        if(r)return r;
        c->initialized=true;
        // Publish ownership immediately; retain on failed teardown.
        auto* raw=c.release();*out=raw;
        r=result(NVSDK_NGX_VULKAN_GetCapabilityParameters(&raw->params));
        int available=0;
        if(!r&&raw->params)r=result(NVSDK_NGX_Parameter_GetI(raw->params,NVSDK_NGX_Parameter_SuperSampling_Available,&available));
        if(!r&&(!raw->params||!available))r=invalid;
        int update=0;
        if(!r&&NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(raw->params,NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver,&update))&&update)r=invalid;
        if(r){if(close_context(raw)==0)*out=nullptr;return r;}
        return 0;
    }catch(...){return invalid;}
}
extern "C" uint32_t fs_ngx_optimal(void* ctx,Extent output,uint32_t quality,Optimal* out) noexcept {
    auto* c=static_cast<Context*>(ctx);float sharp=0;*out={};out->output=output;
    return result(NGX_DLSS_GET_OPTIMAL_SETTINGS(c->params,output.w,output.h,static_cast<NVSDK_NGX_PerfQuality_Value>(quality),
        &out->recommended.w,&out->recommended.h,&out->maximum.w,&out->maximum.h,&out->minimum.w,&out->minimum.h,&sharp));
}
extern "C" uint32_t fs_ngx_create(void* ctx,uint64_t cmd,Optimal size,uint32_t quality) noexcept {
    auto* c=static_cast<Context*>(ctx);NVSDK_NGX_DLSS_Create_Params p{};
    p.Feature.InWidth=size.recommended.w;p.Feature.InHeight=size.recommended.h;
    p.Feature.InTargetWidth=size.output.w;p.Feature.InTargetHeight=size.output.h;
    p.Feature.InPerfQualityValue=static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    p.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR|NVSDK_NGX_DLSS_Feature_Flags_MVLowRes|NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    auto r=result(NGX_VULKAN_CREATE_DLSS_EXT1(c->device,handle<VkCommandBuffer>(cmd),1,1,&c->feature,c->params,&p));
    return r?r:(c->feature?0:invalid);
}
static NVSDK_NGX_Resource_VK resource(const Image& i){
    VkFormat f=i.format==1?VK_FORMAT_R16G16B16A16_SFLOAT:i.format==2?VK_FORMAT_R32_SFLOAT:VK_FORMAT_R16G16_SFLOAT;
    return NVSDK_NGX_Create_ImageView_Resource_VK(handle<VkImageView>(i.view),handle<VkImage>(i.image),
        VkImageSubresourceRange{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1},f,i.extent.w,i.extent.h,i.writable!=0);
}
extern "C" uint32_t fs_ngx_eval(void* ctx,uint64_t cmd,const Images* images,Frame frame) noexcept {
    auto* c=static_cast<Context*>(ctx);
    auto color=resource(images->color),depth=resource(images->depth),motion=resource(images->motion),output=resource(images->output);
    NVSDK_NGX_VK_DLSS_Eval_Params p{};
    p.Feature.pInColor=&color;p.Feature.pInOutput=&output;p.pInDepth=&depth;p.pInMotionVectors=&motion;
    p.InRenderSubrectDimensions={images->color.extent.w,images->color.extent.h};
    p.InJitterOffsetX=frame.jitter_x;p.InJitterOffsetY=frame.jitter_y;p.InReset=frame.reset!=0;
    p.InMVScaleX=1;p.InMVScaleY=1;p.InPreExposure=frame.pre_exposure;p.InExposureScale=1;p.InFrameTimeDeltaInMsec=frame.frame_ms;
    return result(NGX_VULKAN_EVALUATE_DLSS_EXT(handle<VkCommandBuffer>(cmd),c->feature,c->params,&p));
}
extern "C" uint32_t fs_ngx_close(void* ctx) noexcept {return close_context(static_cast<Context*>(ctx));}
