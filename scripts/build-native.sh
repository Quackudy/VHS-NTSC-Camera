#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
ndk_dir="$ANDROID_HOME/ndk/28.2.13676358"
linker="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
[[ -x "$linker" ]] || { echo "Install NDK 28.2.13676358 in Android SDK Manager." >&2; exit 1; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
# NDK r28 supports 16 KiB page alignment; make it explicit for the Rust cdylib.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cargo build --manifest-path native/Cargo.toml --locked --release --target aarch64-linux-android
mkdir -p app/src/main/jniLibs/arm64-v8a
cp native/target/aarch64-linux-android/release/libvhs_native.so app/src/main/jniLibs/arm64-v8a/
