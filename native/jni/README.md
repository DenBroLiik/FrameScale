# Optional Linux JNI bridge (ABI 1)

Build using JDK 21, C compiler, Vulkan/EGL/GL development headers and system loaders:
```sh
sh native/jni/build.sh
java -cp build/native/classes by.xopilot.framescale.NativeBridgeTest missing
java -Dframescale.native.path="$PWD/build/native/libframescale_native.so" -cp build/native/classes by.xopilot.framescale.NativeBridgeTest loaded
```
For Minecraft, add the same absolute `-Dframescale.native.path=...` JVM argument in the instance launcher, using only a trusted locally built library. No automatic downloads or library search. Restart after changing library/path; ABI mismatch or load errors disable the bridge for that session. A native library has full process privileges and cannot be sandboxed by Java.

`/framescale native probe` captures GL device and driver UUIDs from the actual game's render context, then asynchronously enumerates Vulkan physical devices using those identities. `/framescale native` reports the last result. Linux x86_64 only. Missing GL FD memory/semaphore extensions block the probe. This does not require an EGL context on the Vulkan worker and does not create a logical device, allocate shared images or submit GPU work. Vulkan external format capability queries cover RGBA8, RGBA16F input/storage output, R32F depth copy and RG16F motion vectors. Capability support does not establish OpenGL import compatibility, usable shader inputs, performance or NGX support. The isolated hardware interop probe is still required. A driver call may hang/crash despite the Java worker; there is no in-process cancellation or isolation from a native crash. No native files are bundled in the mod JAR.
