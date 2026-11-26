# Linux GPU interop probe

Standalone diagnostic, not loaded by Minecraft. Uses system Vulkan/EGL/OpenGL headers and loader, no proprietary SDK. C is used here to avoid extra runtime dependencies in the Java mod. The eventual production integration is a separate task.

Arch Linux: install the development headers and Vulkan loader if missing (packages vulkan-headers, vulkan-icd-loader, libglvnd), and use the existing GPU driver.
Build and run from repository root:
```sh
cc -std=c11 -O2 -Wall -Wextra native/interop-probe/probe.c native/interop-probe/interop_image.c -o /tmp/framescale-interop -lvulkan -lEGL -lGL
EGL_PLATFORM=surfaceless timeout 20s /tmp/framescale-interop
```
On hybrid laptops run on the same GPU as Minecraft (e.g. prime-run with the appropriate EGL platform). Pass one of the lowercase 32-digit GL UUIDs printed by /framescale gpu as the argument to the executable. If the EGL context picks a different GPU/driver, the probe refuses to share the image. A successful result requires matching device AND driver UUID.

Exit 0: 16 verified Vulkan → OpenGL → Vulkan roundtrips (1024 RGBA8 pixels checked in each direction). One dedicated shared image, two persistent imported binary semaphores, one reusable Vulkan command buffer/fence and host verification buffer. Each round transfers queue ownership in both directions, checks distinct contents, then reuses the allocations; the last round consumes the return semaphore and retains Vulkan ownership. Non-coherent verification memory is invalidated before host reads. Exit 77: unsupported environment/capability (not a pass). Exit 1: execution error. Exit 2: usage. Timeout: failure. Keep the outer timeout; OpenGL readback/driver calls can block. The diagnostic uses CPU-side fence waits and readbacks only for validation, not for transport. No production frame loop, latency claim, HDR formats, NGX or Photon integration. Hardware verification is required.

References: https://docs.vulkan.org/guide/latest/extensions/external.html and https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt

## Shared resource module

`interop_image.h/.c` owns the imported RGBA8 texture, Vulkan allocation and two binary semaphores, with bounded extents and cleanup on partial initialization failure. The roundtrip probe now calls that implementation. The module needs an existing EGL OpenGL context and a verified matching Vulkan device; callers must guarantee thread ownership, matching external barriers, semaphore ordering and quiescence before destruction. No CPU fence wait or readback is inside the module. The probe retains those for verification. This is a reusable C module, not a JNI library or production scheduling backend. CI exercises invalid arguments and empty lifecycle without a GPU; real allocation and synchronization still require NVIDIA testing.
