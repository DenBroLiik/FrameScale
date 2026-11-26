#ifndef FRAMESCALE_INTEROP_IMAGE_H
#define FRAMESCALE_INTEROP_IMAGE_H
#include <stdint.h>
#include <vulkan/vulkan.h>
#include <GL/gl.h>
#include <GL/glext.h>

/* Linux-only resource owner. Caller provides a current GL context and a
 * verified same-device Vulkan 1.1 device with opaque FD extensions enabled.
 * No queue submissions, host waits or image readbacks in this module.
 * Calls must stay on the owner GL thread. Do not copy this object.
 * Zero-initialize before create; destroy only after all GPU work retires. */
typedef struct {
    VkDevice device;
    VkImage image;
    VkDeviceMemory memory;
    VkSemaphore to_gl, to_vk;
    GLuint texture, gl_memory, gl_to_gl, gl_to_vk;
    PFNGLDELETEMEMORYOBJECTSEXTPROC delete_memory;
    PFNGLDELETESEMAPHORESEXTPROC delete_semaphore;
    PFNGLWAITSEMAPHOREEXTPROC wait_semaphore;
    PFNGLSIGNALSEMAPHOREEXTPROC signal_semaphore;
} FsInteropImage;

int fs_interop_valid_extent(uint32_t width,uint32_t height);
int fs_interop_create(FsInteropImage *out,VkPhysicalDevice gpu,VkDevice device,
                      uint32_t width,uint32_t height);
void fs_interop_destroy(FsInteropImage *image);
/* These enqueue synchronization only, not completion. Caller must perform
 * matching Vulkan external queue-family ownership transfers in GENERAL layout.
 * gl_wait: after Vulkan signal. gl_signal: after GL work and before VK wait.
 * Caller is responsible for semaphore order, glFlush and GPU retirement. */
int fs_interop_gl_wait(const FsInteropImage *image);
int fs_interop_gl_signal(const FsInteropImage *image);
#endif
