#pragma once
#include "frame_contract.hpp"
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_vk.h>
#include <string>
#include <thread>
#include <vector>

namespace fs {
/* Existing caller-owned Vulkan context, with SDK-required extensions and features
 * enabled. One NGX session in this process; all methods on its owner thread.
 * Command buffers must be recording and outside render passes. The caller owns
 * submission, barriers, image layouts, interop, retirement and synchronization.
 * Explicit close() AFTER GPU retirement, before destroying the Vulkan device.
 * No implicit waits or submission on the frame path. */
class DlssBackend final {
public:
    DlssBackend()=default;
    DlssBackend(const DlssBackend&)=delete;
    DlssBackend& operator=(const DlssBackend&)=delete;
    DlssBackend(DlssBackend&&)=delete;
    DlssBackend& operator=(DlssBackend&&)=delete;
    ~DlssBackend()=default;
    bool open(VkInstance,VkPhysicalDevice,VkDevice,const std::string& projectId,
              const std::wstring& dataPath,const std::wstring& sdkPath);
    bool optimal(Extent output,NVSDK_NGX_PerfQuality_Value quality,Optimal& out);
    bool create(VkCommandBuffer,const Optimal&,NVSDK_NGX_PerfQuality_Value);
    bool evaluate(VkCommandBuffer,NVSDK_NGX_Resource_VK& color,NVSDK_NGX_Resource_VK& depth,
                  NVSDK_NGX_Resource_VK& motion,NVSDK_NGX_Resource_VK& output,const FrameInfo&);
    // Call only after all creation/evaluation command buffers have retired or been discarded.
    bool close();
    const std::string& error() const {return error_;}
private:
    bool check(NVSDK_NGX_Result,const char*);
    bool fail(const char*);
    bool owner() const {return owner_==std::this_thread::get_id();}
    VkDevice device_=VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* params_=nullptr;
    NVSDK_NGX_Handle* handle_=nullptr;
    std::thread::id owner_;
    Optimal size_{};
    bool first_=true,init_=false,usable_=false;
    std::string project_,error_;
    std::wstring data_,sdk_;
    const wchar_t* search_[1]{};
    NVSDK_NGX_FeatureCommonInfo common_{};
};
}
