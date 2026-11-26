#!/bin/sh
set -eu
cd "$(dirname "$0")/rust"
: "${DLSS_SDK:?Set DLSS_SDK to the authorized local SDK path}"
cargo build --release --locked --features ngx
