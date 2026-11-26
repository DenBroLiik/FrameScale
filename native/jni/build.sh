#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
out="${1:-build/native}"
mkdir -p "$out/headers" "$out/classes"
"$JAVA_HOME/bin/javac" -h "$out/headers" -d "$out/classes" src/main/java/by/xopilot/framescale/NativeBridge.java tests/NativeBridgeTest.java
# Build a cdylib only; the normal Rust crate keeps its rlib/staticlib targets.
cargo rustc --manifest-path native/dlss/rust/Cargo.toml --target-dir "$out/cargo" \
  --release --locked --features jni --lib --crate-type cdylib
cp "$out/cargo/release/libframescale_dlss.so" "$out/libframescale_native.so"
