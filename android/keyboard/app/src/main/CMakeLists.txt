# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
#
# Builds the single JNI library the keyboard loads: libnewpgp_mobile.so.
# The Rust half is a staticlib produced by cargo-ndk; the C++ half in cpp/ owns
# JNI string conversion and exception throwing. AGP supplies the NDK toolchain
# file, ANDROID_ABI and ANDROID_PLATFORM automatically.

cmake_minimum_required(VERSION 3.22.1)
project(newpgp_mobile LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Gradle passes the repository-root `target` directory so no local Windows
# path is baked into this file. The fallback assumes the standard layout:
# <repo>/android/keyboard/app/src/main/CMakeLists.txt -> <repo>/target.
get_filename_component(NEWPGP_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(NEWPGP_RUST_TARGET_DIR "${NEWPGP_REPO_ROOT}/crates/newpgp-mobile/target" CACHE PATH
    "Cargo target directory containing cargo-ndk static libraries")

# cargo-ndk uses LLVM triples; CMake uses Android ABI names.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(NEWPGP_RUST_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(NEWPGP_RUST_TRIPLE "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(NEWPGP_RUST_TRIPLE "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
  set(NEWPGP_RUST_TRIPLE "i686-linux-android")
else()
  message(FATAL_ERROR "Unsupported ANDROID_ABI for newpgp-mobile: ${ANDROID_ABI}")
endif()

set(NEWPGP_RUST_STATIC
    "${NEWPGP_RUST_TARGET_DIR}/${NEWPGP_RUST_TRIPLE}/release/libnewpgp_mobile.a")

if(NOT EXISTS "${NEWPGP_RUST_STATIC}")
  message(FATAL_ERROR
    "Missing Rust static library: ${NEWPGP_RUST_STATIC}\n"
    "Run the Gradle task :app:buildNewpgpRust first (it invokes cargo-ndk).")
endif()

add_library(newpgp_mobile_rust STATIC IMPORTED GLOBAL)
set_target_properties(newpgp_mobile_rust PROPERTIES
    IMPORTED_LOCATION "${NEWPGP_RUST_STATIC}")

# Deterministic output: libnewpgp_mobile.so, loaded with
# System.loadLibrary("newpgp_mobile") from Kotlin.
add_library(newpgp_mobile SHARED cpp/newpgp_jni.cpp)
set_target_properties(newpgp_mobile PROPERTIES OUTPUT_NAME newpgp_mobile)
target_include_directories(newpgp_mobile PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/cpp")
target_link_libraries(newpgp_mobile newpgp_mobile_rust m dl)
