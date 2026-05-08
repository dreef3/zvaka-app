#!/usr/bin/env bash
# Installs GCC, Bazelisk (→ Bazel 7.6.1), and Android NDK r29 on the GCP spot VM.
# The LiteRT-LM WORKSPACE uses @rules_android_ndk (loaded from the @litert transitive deps)
# which supports NDK r26+. Do NOT use the built-in Bazel android_ndk_repository — it requires
# NDK r21 or older (pre-platforms/ removal, pre -gcc-toolchain deprecation).
# Safe to re-run — checks before installing.
set -euo pipefail

NDK_VERSION="r29"
NDK_ZIPNAME="android-ndk-${NDK_VERSION}-linux.zip"
NDK_URL="https://dl.google.com/android/repository/${NDK_ZIPNAME}"
NDK_INSTALL_DIR="$HOME/android-ndk"
NDK_HOME="$NDK_INSTALL_DIR/android-ndk-${NDK_VERSION}"

echo "=== Host C++ compiler ==="
if command -v gcc &>/dev/null; then
  echo "GCC already installed: $(gcc --version | head -1)"
else
  echo "Installing GCC..."
  sudo apt-get install -y gcc g++ unzip
fi

echo "=== Bazel ==="
if command -v bazel &>/dev/null; then
  echo "Bazel/bazelisk already installed at $(which bazel)"
else
  echo "Installing Bazelisk (targets Bazel via .bazelversion)..."
  BAZELISK_URL="https://github.com/bazelbuild/bazelisk/releases/download/v1.22.0/bazelisk-linux-amd64"
  curl -fsSL "$BAZELISK_URL" -o /tmp/bazelisk
  chmod +x /tmp/bazelisk
  sudo mv /tmp/bazelisk /usr/local/bin/bazel
  echo "Bazel (via bazelisk) installed at $(which bazel)"
fi

echo "=== Android NDK $NDK_VERSION ==="
if [[ -d "$NDK_HOME" ]]; then
  echo "NDK already installed at $NDK_HOME"
else
  echo "Downloading NDK $NDK_VERSION (~1.5GB)..."
  mkdir -p "$NDK_INSTALL_DIR"
  curl -fL "$NDK_URL" -o "/tmp/${NDK_ZIPNAME}"
  echo "Extracting NDK..."
  unzip -q "/tmp/${NDK_ZIPNAME}" -d "$NDK_INSTALL_DIR"
  rm "/tmp/${NDK_ZIPNAME}"
  echo "NDK installed at $NDK_HOME"
fi

echo ""
echo "=== Done ==="
echo "Run this in your shell (or add to ~/.bashrc):"
echo "  export ANDROID_NDK_HOME=$NDK_HOME"
