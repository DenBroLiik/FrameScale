# Optional NVIDIA NGX Vulkan adapter

Source-only experimental adapter, based on the public API declarations at NVIDIA/DLSS commit 9981c6350dc58c0ce21f024fd65e31e851c31689.
No SDK files, NGX runtime or SDK static library are copied into FrameScale. Review the NVIDIA RTX SDK license before obtaining or building against the SDK; acceptance is the developer's responsibility (a parent/legal guardian must consent if the user is below the applicable age of majority). Use an authorized SDK installation and an application-specific project ID, not another application's identifier.

Build with a local SDK path and Vulkan headers:
```sh
DLSS_SDK=/absolute/path/to/DLSS sh native/dlss/build.sh
```
Produces a FrameScale-only static archive (not a runnable program). The application must link the matching SDK NGX library and satisfy its runtime/dependency requirements separately. Ordinary CI tests the SDK-independent frame contract only. SDK compilation, linking and execution have not yet been verified.

## API and ownership

- `open`: caller supplies a matching NVIDIA Vulkan instance, physical device and logical device, stable project ID, writable data path and SDK feature library search path. Caller must query NGX feature instance/device extension requirements before creating Vulkan objects and enable the required features. No fallback to a different GPU.
- `optimal`: actual NGX Super Resolution optimal/min/max extents, not an assumed scale ratio.
- `create`: records feature creation at the exact recommended extent; caller submits and retires initialization before evaluation.
- `evaluate`: records the actual NGX call into a recording command buffer. HDR RGBA16F input/output, R32F device-depth copy (normal non-reversed convention, not linear eye distance), RG16F current-to-previous motion in render pixels without jitter, jitter in input pixels, positive pre-exposure, frame time in ms. Auto-exposure flag. First frame resets history; caller must flag camera cuts, world/shader changes etc. Output must have storage usage; all images must be in SDK-required layouts, resident and externally synchronized. No overlapping allocations, including distinct image handles aliasing memory. No dynamic render size, no DLAA/FG claim, no production Photon integration.
- `close`: explicit GPU retirement/discard of recorded work required before release. No implicit vkDeviceWaitIdle or per-frame CPU readback. If NGX teardown fails, stop using the session; don't destroy the underlying device before resolving the lifetime. No automatic destructor cleanup of a live session.

The adapter does not own the Vulkan context or queue, does not capture Iris textures and is not connected to JNI or Minecraft yet. Thread/ownership and format contracts are necessary, not proof of SDK compatibility. A failed create/evaluate disables further use until close. Next gate: build with the actual SDK, validate NGX-required Vulkan extensions/features and device initialization, execute a synthetic-frame test on NVIDIA, then wire verified Photon pre-TAA inputs.

References: https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689 and https://github.com/NVIDIA/DLSS/blob/9981c6350dc58c0ce21f024fd65e31e851c31689/LICENSE.txt
