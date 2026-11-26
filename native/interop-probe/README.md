# Linux GPU interop probe

Standalone diagnostic, not loaded by Minecraft. Uses system Vulkan/EGL/OpenGL headers and loader, no proprietary SDK. C is used here to avoid extra runtime dependencies in the Java mod. The eventual production integration is a separate task.

Arch Linux: install the development headers and Vulkan loader if missing (packages vulkan-headers, vulkan-icd-loader, libglvnd), and use the existing GPU driver.
Build and run from repository root:
```sh
cc -std=c11 -O2 -Wall -Wextra native/interop-probe/probe.c -o /tmp/framescale-interop -lvulkan -lEGL -lGL
EGL_PLATFORM=surfaceless timeout 20s /tmp/framescale-interop
```
On hybrid laptops run on the same GPU as Minecraft (e.g. prime-run with the appropriate EGL platform). Pass one of the lowercase 32-digit GL UUIDs printed by /framescale gpu as the argument to the executable. If the EGL context picks a different GPU/driver, the probe refuses to share the image. A successful result requires matching device AND driver UUID.

Exit 0: verified Vulkan clear, dedicated exported RGBA8 image, imported memory and binary semaphore, external queue ownership release, GL wait and 64 correct pixels. Exit 77: unsupported environment/capability (not a pass). Exit 1: execution error. Exit 2: usage. Timeout: failure. Diagnostic uses a fence wait and readback; these are not intended for real-time rendering. Tests only Vulkan → GL; reverse direction, frame loop, production synchronization, HDR formats, NGX, and Photon buffers are still unimplemented.

References: https://docs.vulkan.org/guide/latest/extensions/external.html and https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt
