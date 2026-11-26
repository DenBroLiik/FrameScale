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

## Rust GL import (alpha.5)

Linux EGL only. From rust/: `cargo build --locked --features gl-interop --bin gl_import_probe`, then `EGL_PLATFORM=surfaceless timeout 20s target/debug/gl_import_probe` on the intended NVIDIA driver. Exit 77 means missing EGL/extensions, not a pass; other errors fail. Tests import and destroy five kinds of Vulkan-backed GL textures and binary semaphores, without submissions or pixel readback. `ImportedImage` requires a pristine external allocation and current matching EGL context. Successful import consumes the FD per EXT_external_objects_fd; preexisting GL errors are rejected. After exposing raw handles or recording synchronization, explicitly close only after both APIs retire work; on failure abandon the diagnostic process. The unsafe wait/signal methods require matching Vulkan EXTERNAL ownership barriers and correct binary semaphore order, which the next roundtrip must implement. No Minecraft/GLX integration, no actual DLSS. Old C probe remains available. Reference: https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt and EXT_external_objects_fd.txt.

## Rust roundtrip (alpha.6)

From rust/: `cargo build --locked --features gl-interop --bin gl_import_probe`; `timeout 20s target/debug/gl_import_probe --local` tests local Vulkan clear/readback. `EGL_PLATFORM=surfaceless timeout 30s target/debug/gl_import_probe --roundtrip` tests 16 bidirectional RGBA8 exchanges on the matched NVIDIA GPU. No argument still tests import-only of all five formats. Exit 77 is unsupported EGL/GL extensions, not successful interop. Uses one 8x8 shared image, two binary semaphores, GENERAL layout, EXTERNAL ownership transfer and one reusable verification buffer. CPU waits/readback only for diagnostics. On any roundtrip error terminate the dedicated process rather than destroy potentially in-flight resources. No in-game execution; use the outer timeout as GL calls may block. Hardware validation pending; not a latency benchmark.

## Rust JNI (alpha.7)

`sh native/jni/build.sh` from the repository root builds the optional ABI-1 diagnostic library with the `jni` feature. No NVIDIA SDK is required. The game uses the shared Rust Vulkan inventory for UUID matching. Interop and DLSS execution remain standalone/unconnected to this JNI path. See native/jni/README.md.

## Nonblocking scheduler (alpha.8)

Run `cargo run --locked --features vulkan --bin scheduler_probe` from rust/. It submits 128 empty command buffers with three reusable slots, polls fences and checks backpressure; local images remain allocated per slot but are not referenced by the commands. `try_begin` returns None when full, `submit` accepts bounded wait/signal lists, `poll` reports Vulkan completion, and unsafe `release` additionally requires external consumer retirement. Tickets carry a scheduler ID and generation; stale/foreign tickets fail. No explicit fence/device idle waits in scheduler methods; driver calls themselves can still block. One queue owner only. On Vulkan error the scheduler is poisoned; dedicated diagnostic process must exit without destroying in-flight resources. Explicit close only when drained, never implicit cleanup of exposed work. The NGX session is still single-flight and not connected to this ring, and GL synchronization/presentation must be implemented before real frame use.

## Local HDR pipeline (alpha.9)

Run `cargo run --locked --features vulkan --bin pipeline_probe` from rust/. Three slots each hold HDR input/output, depth and motion plus a host verification buffer. Synthetic 32x24 HDR colors are blitted to 64x48, each pixel compared exactly as binary16, with 128 frames and explicit release after readback. Depth and motion are initialized only, not used in reconstruction. No per-frame allocation or explicit fence wait; diagnostic CPU map/readback and a short poll sleep remain. Allocation budget checks estimated and actual image/buffer sizes (actual check occurs after each slot's allocation; not a driver-wide VRAM cap). Failures require standalone process exit rather than destroying possibly live work. No GL export/import, NGX, game hooks or performance benchmark in this pipeline.
