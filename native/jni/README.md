# Optional Linux JNI bridge (ABI 1)

Build using JDK 21, Rust 1.85+ / Cargo and the system Vulkan loader development package:
```sh
sh native/jni/build.sh
java -cp build/native/classes by.xopilot.framescale.NativeBridgeTest missing
java -Dframescale.native.path="$PWD/build/native/libframescale_native.so" -cp build/native/classes by.xopilot.framescale.NativeBridgeTest loaded
```
For Minecraft, add the same absolute `-Dframescale.native.path=...` JVM argument in the instance launcher, using only a trusted locally built library. No automatic downloads or library search. Restart after changing library/path; ABI mismatch or load errors disable the bridge for that session. A native library has full process privileges and cannot be sandboxed by Java.

`/framescale native probe` captures GL device and driver UUIDs from the actual game's render context, then asynchronously enumerates Vulkan physical devices using those identities. `/framescale native` reports the last result. Linux x86_64 only. Missing GL FD memory/semaphore extensions block the probe. This does not require an EGL context on the Vulkan worker and does not create a logical device, allocate shared images or submit GPU work. Vulkan external format capability queries cover RGBA8, RGBA16F input/storage output, R32F depth copy and RG16F motion vectors. Capability support does not establish OpenGL import compatibility, usable shader inputs, performance or NGX support. The isolated hardware interop probe is still required. A driver call may hang/crash despite the Java worker; there is no in-process cancellation or isolation from a native crash. No native files are bundled in the mod JAR.

## Rust implementation (alpha.7)

The normal build now compiles `native/dlss/rust/src/jni_bridge.rs` as a cdylib using `jni-sys` and `ash`; ABI 1 and the Java commands are unchanged. The old bridge.c remains historical and is not linked. Diagnostic matching accepts any exact device/driver UUID pair to report capability (including Intel/Mesa); production NVIDIA selection remains strict in the Rust interop manager. Zero UUIDs, bad lengths and ambiguous device matches are rejected. JNI exceptions are preserved; Rust panics are contained at the boundary when unwinding is available. Native crashes or hangs cannot be contained. CI checks a real Mesa device match through Java, but does not prove NVIDIA interop or NGX execution. No C/C++ or EGL is needed for this diagnostic library. No game rendering changes.
