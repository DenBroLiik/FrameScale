#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
: "${DLSS_SDK:?Set DLSS_SDK to the locally obtained NVIDIA SDK after reviewing its license}"
test -f "$DLSS_SDK/include/nvsdk_ngx_helpers_vk.h"
test -f "$DLSS_SDK/lib/Linux_x86_64/libnvsdk_ngx.a"
out="${1:-build/dlss}"
mkdir -p "$out"
# Build our adapter only; do not copy or redistribute any SDK files.
c++ -std=c++20 -O2 -Wall -Wextra -fPIC -isystem "$DLSS_SDK/include" \
  -c native/dlss/dlss_backend.cpp -o "$out/dlss_backend.o"
ar rcs "$out/libframescale_dlss.a" "$out/dlss_backend.o"
c++ -std=c++20 -O2 -Wall -Wextra -Werror native/dlss/test_frame_contract.cpp -o "$out/test_frame_contract"
"$out/test_frame_contract"
