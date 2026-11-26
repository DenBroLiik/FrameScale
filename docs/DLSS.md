# Real DLSS backend — integration gate

FrameScale uses Photon TAAU, not DLSS. No fake DLSS switch is exposed. Native Linux DLSS Super Resolution requires an NVIDIA-supported Vulkan/NGX implementation; Minecraft 1.21.1 Iris renders using OpenGL. No replacement of GPUTape/Sodium/Iris or forced VulkanMod migration is acceptable.

## Progress in 0.7.0-alpha.7

Rust JNI diagnostic replaces the C library, keeping ABI 1. Minecraft's explicit native probe now uses the same ash inventory as the standalone device manager. No GL import, queue submission or NGX evaluation inside Minecraft yet.

## Progress in 0.7.0-alpha.6

Rust bidirectional RGBA8 diagnostic records external queue ownership barriers and reuses memory, command buffer, fence and two binary semaphores across 16 exchanges. Distinct color patterns verified in both APIs. Local Vulkan readback smoke independent of GL external support. Hardware execution, HDR roundtrip, production scheduling, JNI and NGX remain unverified or unimplemented.

## Progress in 0.7.0-alpha.5

Rust OpenGL external object import pairs the ash image and FD semaphores with GL texture and semaphore objects. EGL only; exact same-device/driver matching, partial rollback, explicit retirement and GL wait/signal entrypoints. Import-only probe, no Vulkan external ownership barriers or actual roundtrip in Rust yet. Hardware compatibility and NGX remain unverified.

## Progress in 0.7.0-alpha.4

Rust Vulkan-side shared image module allocates dedicated RGBA8/RGBA16F/R32F/RG16F images, views and two binary semaphores; exports owned memory/semaphore FDs when enabled and supported. Local resource smoke tests are SDK independent. OpenGL import and external ownership transitions still use the older separate C diagnostic; Rust roundtrip and actual RTX/NGX remain unverified.

## Progress in 0.7.0-alpha.3

Optional Rust ash device manager creates diagnostic Vulkan instances/logical devices, queries external candidate image formats and semaphores, and submits an empty command buffer with bounded fence wait. NVIDIA path requires exact GL device/driver UUID. The SDK extension/feature discovery and device feature chain for NGX remain unimplemented. Separate Mesa smoke path is not interop or DLSS verification.

## Progress in 0.7.0-alpha.2

Active adapter control plane is Rust: device identity matching, extension validation, frame checks, explicit retirement and failure states. Minimal C++ shim only translates NGX API and resource structures. SDK-independent Rust tests; actual Vulkan context creation, SDK compilation and hardware validation remain next gates.

## Progress in 0.7.0-alpha.1

Source-only NGX adapter under native/dlss uses actual NVIDIA Vulkan Super Resolution API calls for initialization, optimal dimensions, feature creation, evaluation and cleanup. Optional external SDK, no vendored SDK material. SDK-independent frame contract tests are in CI; SDK build/link and GPU execution still pending. Caller-owned Vulkan context, no JNI or Photon hookup yet.

## Progress in 0.6.0-alpha.7

Explicitly loaded Linux JNI diagnostic bridge compares actual Minecraft GL device/driver UUIDs to Vulkan physical devices, checks candidate external formats and semaphore export support. No native context creation or GL calls on worker; no logical device, shared allocation in game or NGX. Hardware roundtrip still required.

## Progress in 0.6.0-alpha.6

Reusable native resource owner extracted from the roundtrip diagnostic. Centralizes shared RGBA8 allocation, FD import and semaphore lifecycle. Not yet loaded by Minecraft. Existing EGL context only; no JNI, HDR, temporal input capture or NGX. Previous software CI skipped actual interop.

## Progress in 0.6.0-alpha.5

Bidirectional RGBA8 diagnostic: 16 roundtrips using two binary semaphores and a single shared allocation; both directions checked with CPU readback. Explicit external ownership transitions and non-coherent verification buffer handling. Hardware success still unverified; no production scheduling, NGX or shader inputs.

## Progress in 0.6.0-alpha.4

A standalone Linux Vulkan → GL smoke test implements the first external memory/semaphore experiment. See native/interop-probe/README.md. It tests RGBA8 and a single producer/consumer exchange with dedicated allocation and explicit external ownership. Not a real-time backend; no GL → Vulkan handoff, HDR, NGX or in-game interop. Hardware verification pending.

## Implementation milestones

1. Inspect the actual modpack audit, shader pack revision and GPUTape GL hooks.
2. Establish same-device GL/Vulkan external-memory and semaphore interop; confirm adapter UUID, handle types, synchronization, layout transitions and resource lifetime. No CPU image copies. On unsupported driver, stay on Photon.
3. Export linear or documented input color, depth, correct object/camera motion vectors, jitter and exposure from a compatible shader pass before temporal reconstruction and HUD. Use shader-specific integration; camera-only motion vectors are insufficient for moving entities. Handle hand, particles, water, translucent materials and Distant Horizons.
4. Initialize supported NGX SDK; verify license, runtime redistributables, driver and device capability. Allocate output at native framebuffer size; reset history on teleport, camera cuts, resolution and pack changes. Exactly one temporal upscaler.
5. Compare native, Photon and DLSS with identical scenes, capture p50/p95, 1% low, GPU time, VRAM, frame pacing and moving-image artifacts. Gate on measured net gain after interop/NGX overhead. Do not claim lossless rendering.

Reference: https://developer.nvidia.com/rtx/streamline/get-started and https://github.com/NVIDIA/DLSS . Integration must follow the applicable SDK license. No proprietary binaries included in this release.
