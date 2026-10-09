#!/bin/sh
# Сборка нативных .so для Task 2 (Windows: запускать из Git Bash).
# Проверено: NDK 27.0.12077973 + CMake 3.22.1 (из Android SDK), llama.cpp v0.6.0.
# NDK 21 НЕ подходит (нет std::filesystem в libc++ -> ошибка линковки).
# На выходе в androidApp/jniLibs/<abi>/ лежат 6 библиотек:
#   libtask2bridge.so (наш JNI-мост) + libllama.so + libggml*.so + libomp.so.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-C:/Android/SDK}"
NDK="$SDK/ndk/27.0.12077973"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
TAG="v0.6.0"
case "$(uname -s)" in
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  Linux*)  HOST_TAG="linux-x86_64" ;;
  *)       HOST_TAG="windows-x86_64" ;;
esac

if [ ! -f "$ROOT/androidApp/cpp/llama.cpp/include/llama.h" ]; then
  echo "Клонирую llama.cpp $TAG (shallow)..."
  git clone --depth 1 --branch "$TAG" \
    https://github.com/ggml-org/llama.cpp.git "$ROOT/androidApp/cpp/llama.cpp"
fi

# arch-папка libomp.so в NDK зависит от ABI
omp_for() {
  case "$1" in
    arm64-v8a) echo "aarch64" ;;
    x86_64)    echo "x86_64" ;;
    *)         echo "unsupported ABI: $1" >&2; exit 1 ;;
  esac
}

build_abi() {
  ABI="$1"
  echo "=== $ABI ==="
  "$CMAKE" -S "$ROOT/androidApp/cpp" -B "$ROOT/build/llama-$ABI" -G Ninja \
    "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release > /dev/null
  "$CMAKE" --build "$ROOT/build/llama-$ABI" --config Release
  mkdir -p "$ROOT/androidApp/jniLibs/$ABI"
  cp "$ROOT/build/llama-$ABI/libtask2bridge.so" "$ROOT/androidApp/jniLibs/$ABI/"
  cp "$ROOT/build/llama-$ABI/bin/libllama.so" \
     "$ROOT/build/llama-$ABI/bin/libggml.so" \
     "$ROOT/build/llama-$ABI/bin/libggml-cpu.so" \
     "$ROOT/build/llama-$ABI/bin/libggml-base.so" \
     "$ROOT/androidApp/jniLibs/$ABI/"
  cp "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/lib/clang/18/lib/linux/$(omp_for "$ABI")/libomp.so" \
     "$ROOT/androidApp/jniLibs/$ABI/"
  echo "OK -> androidApp/jniLibs/$ABI/ (6 .so)"
}

for ABI in ${1:-"arm64-v8a x86_64"}; do build_abi "$ABI"; done
