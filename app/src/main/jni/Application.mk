APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_CFLAGS := -O3
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
# 16 KB page size (Android 15+ 64-bit devices); harmless on 4 KB 32-bit builds.
APP_LDFLAGS := -Wl,-z,max-page-size=16384
