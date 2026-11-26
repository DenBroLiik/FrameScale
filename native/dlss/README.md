# Optional NVIDIA NGX Vulkan adapter — Rust

Source-only experimental adapter, based on the public API declarations at NVIDIA/DLSS commit 9981c6350dc58c0ce21f024fd65e31e851c31689.
No SDK files, NGX runtime or SDK static library are copied into FrameScale. Review the NVIDIA RTX SDK license before obtaining or building against the SDK; acceptance is the developer's responsibility (a parent/legal guardian must consent if the user is below the applicable age of majority). Use an authorized SDK installation and an application-specific project ID, not another application's identifier.

Build with a local SDK path and Vulkan headers:
```sh
DLSS_SDK=/absolute/path/to/DLSS sh native/dlss/build.sh
```
The current script builds the Rust library and its minimal FFI shim (not a runnable program). The application must link the matching SDK NGX library and satisfy its runtime/dependency requirements separately. Ordinary CI tests the SDK-independent Rust contract and state machine. SDK compilation, linking and execution have not yet been verified.

## API and ownership

- `open`: caller supplies a matching NVIDIA Vulkan instance, physical device and logical device, stable project ID, writable data path and SDK feature library search path. Caller must query NGX feature instance/device extension requirements before creating Vulkan objects and enable the required features. No fallback to a different GPU.
- `optimal`: actual NGX Super Resolution optimal/min/max extents, not an assumed scale ratio.
- `create`: records feature creation at the exact recommended extent; caller submits and retires initialization before evaluation.
- `evaluate`: records the actual NGX call into a recording command buffer. HDR RGBA16F input/output, R32F device-depth copy (normal non-reversed convention, not linear eye distance), RG16F current-to-previous motion in render pixels without jitter, jitter in input pixels, positive pre-exposure, frame time in ms. Auto-exposure flag. First frame resets history; caller must flag camera cuts, world/shader changes etc. Output must have storage usage; all images must be in SDK-required layouts, resident and externally synchronized. No overlapping allocations, including distinct image handles aliasing memory. No dynamic render size, no DLAA/FG claim, no production Photon integration.
- `close`: explicit GPU retirement/discard of recorded work required before release. No implicit vkDeviceWaitIdle or per-frame CPU readback. If NGX teardown fails, stop using the session; don't destroy the underlying device before resolving the lifetime. No automatic destructor cleanup of a live session.

The adapter does not own the Vulkan context or queue, does not capture Iris textures and is not connected to JNI or Minecraft yet. Thread/ownership and format contracts are necessary, not proof of SDK compatibility. A failed create/evaluate disables further use until close. Next gate: build with the actual SDK, validate NGX-required Vulkan extensions/features and device initialization, execute a synthetic-frame test on NVIDIA, then wire verified Photon pre-TAA inputs.

References: https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689 and https://github.com/NVIDIA/DLSS/blob/9981c6350dc58c0ce21f024fd65e31e851c31689/LICENSE.txt

## Rust migration in 0.7.0-alpha.2

The active source is rust/src/lib.rs and rust/src/ngx.rs. Old C++ backend files are retained as historical reference, not built by the current script or CI. The only active C++ is ngx_shim.cpp, translating the official SDK ABI/helpers. Rust owns state, validation, reset, error handling and thread confinement. Device UUID selection and extension-list merge are pure Rust helpers, not a Vulkan device creator; caller must enumerate and enable SDK-required features. The first Rust scheduler deliberately allows one in-flight frame and requires explicit retirement before reuse. Never call the unsafe methods with unverified handles, resource layouts or pending GPU work. No implicit waits in the library, no automatic destructor for live NGX. An error after recording disables reuse; explicit close requires retirement/discard. Dropping an unclosed session leaks its native resources and prevents another NGX session rather than risking unsafe teardown. Use a dedicated diagnostic process for hardware tests. Default cargo test --locked in rust/ tests without SDK. The opt-in ngx feature needs a local authorized SDK and system headers, has not been compiled or linked with the SDK here. Build produces a Rust library, not JNI or a runnable DLSS mod. Link consumers against SDK and required dependencies separately. No SDK archive distributed.

## Rust Vulkan device manager (alpha.3)

Build and run without NVIDIA SDK (Linux, Rust 1.85+, system Vulkan loader development package):
```sh
cd native/dlss/rust
cargo build --locked --features vulkan --bin vulkan_probe
./target/debug/vulkan_probe
timeout 20s ./target/debug/vulkan_probe --smoke
```
`--match GL_UUID DRIVER_UUID` selects an NVIDIA adapter only when both GL UUIDs match and candidate external formats/extensions are available; UUIDs are 32 hex digits. The current Minecraft diagnostic reports device UUID but not driver UUID; use a trusted GL inventory that also reports driver UUID until JNI is migrated. `--smoke` deliberately permits a software GPU for a standalone diagnostic. It is not a fallback in the matched path. Only minimal logical-device features are enabled; callers must not initialize NGX using this device until SDK-required instance/device extensions and feature chain are integrated. Empty submission/fence is not a DLSS test. Explicit close only after work retires; on error the standalone probe exits rather than destroying potentially live work. No CPU copy or GPU interop tested. `ash` is optional, default crate remains dependency-free.

## Rust shared GPU allocation (alpha.4)

Run `cargo run --locked --features vulkan --bin vulkan_probe -- --resources` in rust/ to test five local image types. `--export GL_UUID DRIVER_UUID` is a matched NVIDIA-only capability and FD export test; it does not import into GL or verify pixels. Each resource has dedicated memory, image view and two binary semaphores. OwnedFd closes unconsumed descriptors; an importer must take ownership only according to its API contract. Allocations not exposed to the caller clean up automatically; exposed allocations require explicit unsafe close after all GPU/GL users retire. Dropping exposed resources intentionally leaks them, and closing the device after such a leak is invalid. Dedicated diagnostic processes only. Actual GL import, barriers, semaphores and roundtrip are still to be migrated. The old C diagnostic is unchanged.
