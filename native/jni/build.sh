#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
out="${1:-build/native}"
mkdir -p "$out/headers" "$out/classes"
"$JAVA_HOME/bin/javac" -h "$out/headers" -d "$out/classes" src/main/java/by/xopilot/framescale/NativeBridge.java tests/NativeBridgeTest.java
cc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -Wl,-z,defs \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$out/headers" \
  native/jni/bridge.c native/interop-probe/interop_image.c \
  -o "$out/libframescale_native.so" -lvulkan -lEGL -lGL
