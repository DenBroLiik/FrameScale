# Real DLSS backend — integration gate

FrameScale 0.3.0 uses Photon TAAU, not DLSS. No fake DLSS switch is exposed. Native Linux DLSS Super Resolution requires an NVIDIA-supported Vulkan/NGX implementation; Minecraft 1.21.1 Iris renders using OpenGL. No replacement of GPUTape/Sodium/Iris or forced VulkanMod migration is acceptable.

## Implementation milestones

1. Inspect the actual modpack audit, shader pack revision and GPUTape GL hooks.
2. Establish same-device GL/Vulkan external-memory and semaphore interop; confirm adapter UUID, handle types, synchronization, layout transitions and resource lifetime. No CPU image copies. On unsupported driver, stay on Photon.
3. Export linear or documented input color, depth, correct object/camera motion vectors, jitter and exposure from a compatible shader pass before temporal reconstruction and HUD. Use shader-specific integration; camera-only motion vectors are insufficient for moving entities. Handle hand, particles, water, translucent materials and Distant Horizons.
4. Initialize supported NGX SDK; verify license, runtime redistributables, driver and device capability. Allocate output at native framebuffer size; reset history on teleport, camera cuts, resolution and pack changes. Exactly one temporal upscaler.
5. Compare native, Photon and DLSS with identical scenes, capture p50/p95, 1% low, GPU time, VRAM, frame pacing and moving-image artifacts. Gate on measured net gain after interop/NGX overhead. Do not claim lossless rendering.

Reference: https://developer.nvidia.com/rtx/streamline/get-started and https://github.com/NVIDIA/DLSS . Integration must follow the applicable SDK license. No proprietary binaries included in this release.
