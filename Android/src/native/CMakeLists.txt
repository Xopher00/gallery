cmake_minimum_required(VERSION 3.22.1)
project("chimera_wrappers")

# Pinned to stable-diffusion.cpp's value (Chimera's master-816-487de75) so the
# shared-GGML build below does not change the tensor layout; must be set
# before llama.cpp's own ggml is configured.
add_compile_definitions(GGML_MAX_NAME=160)

# Build every ggml-cpu ISA variant as a separate .so, picked at runtime by score (see
# ggml_backend_load_best in ggml-backend-reg.cpp), instead of tuning only our own JNI wrapper.
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# Chimera's scripts/manage.py is the single source of truth for the engine versions this
# build must match; fetched below instead of vendoring them as submodules.
set(_MANAGE_PY "${CMAKE_CURRENT_SOURCE_DIR}/../chimera/scripts/manage.py")
foreach(_pair LLAMACPP_VERSION WHISPERCPP_VERSION SDCPP_VERSION)
    file(STRINGS "${_MANAGE_PY}" _line REGEX "^${_pair} = \"[^\"]+\"")
    if(NOT _line)
        message(FATAL_ERROR "Could not find ${_pair} in ${_MANAGE_PY}")
    endif()
    string(REGEX MATCH "\"([^\"]+)\"" _ "${_line}")
    set(${_pair} "${CMAKE_MATCH_1}")
endforeach()

include(FetchContent)
# Keeps re-downloads out of a clean Gradle build by caching under the source tree.
set(FETCHCONTENT_BASE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.deps")

FetchContent_Declare(
        llamacpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG ${LLAMACPP_VERSION}
        GIT_SHALLOW TRUE
        GIT_SUBMODULES ""
)
# Applies, in order, the two stable-diffusion.cpp fixes Chimera carries plus our decoder-only VAE
# patch. Each patch is skipped (via reverse-apply check) if already applied, so reconfiguring an
# already-patched checkout is a no-op; a patch that applies to neither direction fails the
# configure instead of being silently skipped (Chimera's patches/README.md warns this can happen
# silently otherwise).
set(_SDCPP_PATCH_1 "${CMAKE_CURRENT_SOURCE_DIR}/../chimera/scripts/patches/stable-diffusion.cpp-conditioner-compute-failure.patch")
set(_SDCPP_PATCH_2 "${CMAKE_CURRENT_SOURCE_DIR}/../chimera/scripts/patches/stable-diffusion.cpp-graph-cut-budget-clamp.patch")
set(_SDCPP_PATCH_3 "${CMAKE_CURRENT_SOURCE_DIR}/patches/stable-diffusion.cpp-vae-decode-only.patch")
FetchContent_Declare(
        sdcpp
        GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp.git
        GIT_TAG ${SDCPP_VERSION}
        GIT_SHALLOW TRUE
        GIT_SUBMODULES ""
        PATCH_COMMAND ${CMAKE_COMMAND} "-DPATCHES=${_SDCPP_PATCH_1},${_SDCPP_PATCH_2},${_SDCPP_PATCH_3}" -P ${CMAKE_CURRENT_SOURCE_DIR}/apply-patches.cmake
)
FetchContent_Declare(
        whispercpp
        GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
        GIT_TAG ${WHISPERCPP_VERSION}
        GIT_SHALLOW TRUE
        GIT_SUBMODULES ""
)

# Vulkan backend for ggml. Declared and primed before llama.cpp is made available, since
# ggml-vulkan's own CMakeLists.txt (pulled in as a subdirectory of llama.cpp/ggml) runs
# find_package(Vulkan) and find_package(SPIRV-Headers) as soon as that happens.
FetchContent_Declare(
        vulkan-hpp
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
        GIT_TAG v1.3.275
        GIT_SHALLOW TRUE
        GIT_SUBMODULES ""
)
FetchContent_Declare(
        spirv-headers
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
        GIT_TAG vulkan-sdk-1.3.275.0
        GIT_SHALLOW TRUE
)
# Headers only: MakeAvailable would configure their own projects, which build code generators we do not need.
foreach(_hdr vulkan-hpp spirv-headers)
    FetchContent_GetProperties(${_hdr})
    if(NOT ${_hdr}_POPULATED)
        FetchContent_Populate(${_hdr})
    endif()
endforeach()

# SPIRV-Headers ships no CMake package config of its own; ggml-vulkan's
# find_package(SPIRV-Headers CONFIG REQUIRED) needs one, so a minimal one is written here,
# pointing at the fetched headers, and found via SPIRV-Headers_DIR (CMake 3.22 has no
# FetchContent FIND_PACKAGE_ARGS to synthesize this instead).
set(_SPIRV_HEADERS_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-config")
file(WRITE "${_SPIRV_HEADERS_CONFIG_DIR}/SPIRV-HeadersConfig.cmake" "
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES \"${spirv-headers_SOURCE_DIR}/include\")
endif()
")
set(SPIRV-Headers_DIR "${_SPIRV_HEADERS_CONFIG_DIR}" CACHE PATH "" FORCE)

# The NDK sysroot already carries vulkan/vulkan.h (VK_HEADER_VERSION 275, matching the tags
# above), libvulkan.so and glslc; pre-seed FindVulkan's cache variables so
# find_package(Vulkan COMPONENTS glslc REQUIRED) resolves them instead of searching for a
# desktop Vulkan SDK. Every module in this project builds arm64-v8a only (see the abiFilters
# comments in the Gradle files), so the triple is fixed.
set(_NDK_SYSROOT "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot")
set(Vulkan_INCLUDE_DIR "${_NDK_SYSROOT}/usr/include" CACHE PATH "" FORCE)
set(Vulkan_LIBRARY "${_NDK_SYSROOT}/usr/lib/aarch64-linux-android/${ANDROID_PLATFORM_LEVEL}/libvulkan.so" CACHE FILEPATH "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "" FORCE)

set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# ggml-vulkan builds its shader generator as a separate laptop-side project that cannot find the SDK ninja
# on its own; give it a toolchain file naming the laptop compilers and this build's ninja.
find_program(_HOST_CC gcc REQUIRED)
find_program(_HOST_CXX g++ REQUIRED)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-gen-host.cmake"
"set(CMAKE_BUILD_TYPE Release)\nset(CMAKE_C_COMPILER \"${_HOST_CC}\")\nset(CMAKE_CXX_COMPILER \"${_HOST_CXX}\")\nset(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)\n")
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-gen-host.cmake" CACHE FILEPATH "" FORCE)

FetchContent_MakeAvailable(llamacpp)
set(LLAMA_DIR ${llamacpp_SOURCE_DIR})

# vulkan.hpp lives at the Vulkan-Hpp repo root (vulkan/vulkan.hpp); the NDK sysroot only ships
# the C header, and ggml-vulkan.cpp includes the C++ one.
if(TARGET ggml-vulkan)
    # ggml-vulkan only find_package()s SPIRV-Headers and expects them in a system include dir.
    target_include_directories(ggml-vulkan PRIVATE ${vulkan-hpp_SOURCE_DIR} ${spirv-headers_SOURCE_DIR}/include)
endif()

set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)
set(VENDOR_DIR ${LLAMA_DIR}/vendor)

# PRIVATE, not PUBLIC: since llama.cpp v0.4.0 llama-common is a shared library linking llama, and a
# PUBLIC hidden visibility would hide every common_* function our JNI calls.
target_compile_options(
        llama
        PRIVATE
        -fvisibility=hidden -fvisibility-inlines-hidden
)
target_compile_options(
        llama
        PUBLIC
        -ffunction-sections -fdata-sections
)
target_link_options(
        llama
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)

set(SMOLLM_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../smollm/src/main/cpp")

function(build_library target_name)
    add_library(
            ${target_name}
            SHARED
            "${SMOLLM_SRC_DIR}/LLMInference.cpp"
            "${SMOLLM_SRC_DIR}/smollm.cpp"
    )
    target_include_directories(
            ${target_name}
            PUBLIC
            ${COMMON_DIR}
            ${GGML_DIR}/include
            ${GGML_DIR}/src
            ${GGML_DIR}/src/ggml-cpu
            ${LLAMA_DIR}/include
            ${VENDOR_DIR}
    )
    target_compile_options(
            ${target_name}
            PUBLIC
            -fvisibility=hidden -fvisibility-inlines-hidden
    )
    target_compile_options(
            ${target_name}
            PUBLIC
            -ffunction-sections -fdata-sections
            -fvisibility=hidden -fvisibility-inlines-hidden
    )
    target_link_libraries(
            ${target_name}
            android log llama llama-common
    )
    target_link_options(
            ${target_name}
            PRIVATE
            -Wl,--gc-sections -flto
            -Wl,--exclude-libs,ALL
    )
endfunction()

function(build_library_universal target_name)
    build_library(${target_name})
    target_compile_options(
            ${target_name}
            PUBLIC
            -DGGML_USE_CPU -O3
    )
endfunction()

build_library_universal("smollm")

# GGUFReader library
set(TARGET_NAME_GGUF_READER ggufreader)
add_library(${TARGET_NAME_GGUF_READER} SHARED "${SMOLLM_SRC_DIR}/GGUFReader.cpp")
target_include_directories(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        ${GGML_DIR}/include
)
target_compile_options(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections
)
target_link_libraries(
        ${TARGET_NAME_GGUF_READER}
        ggml
)
target_link_options(
        ${TARGET_NAME_GGUF_READER}
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)

# stable-diffusion.cpp only builds its own ggml when no "ggml" target exists
# yet (stable-diffusion.cpp/CMakeLists.txt:321); since llama.cpp already
# defined one above, this reuses it instead of configuring a second copy.
# BUILD_SHARED_LIBS is forced back off (Gradle passes it ON for
# llama.cpp's own targets) so the stable-diffusion library itself stays
# static, avoiding symbol conflicts with the already-built shared ggml.
set(BUILD_SHARED_LIBS OFF)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
set(SD_METAL OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_SYCL OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(sdcpp)
# SD includes ggml internals as "ggml/src/..." from its own bundled ggml, which is not fetched; point it at the
# llama.cpp ggml it actually links so headers and library match.
target_include_directories(stable-diffusion PRIVATE ${llamacpp_SOURCE_DIR})

# JNI shared library: SDInference.cpp only, everything else statically linked
# into the shared ggml/stable-diffusion targets above.
add_library(stablediffusion SHARED "${CMAKE_CURRENT_SOURCE_DIR}/../stablediffusion/src/main/cpp/SDInference.cpp")

target_compile_options(stablediffusion PRIVATE
        -std=c++17
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
        -O3
)

target_link_libraries(stablediffusion
        android
        log
        stable-diffusion
        ggml
)

target_link_options(stablediffusion PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)

# whisper.cpp reuses the "ggml" target defined by llama.cpp above (whisper.cpp/CMakeLists.txt
# guards its own add_subdirectory(ggml) with "if (NOT TARGET ggml)"), so this does not configure
# a second ggml copy.
set(WHISPER_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL               OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2               OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML             OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO           OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/../whisper/src/main/cpp/WhisperInference.cpp")

target_compile_options(whisper_jni PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
        -O3
)

target_link_libraries(whisper_jni
        android
        log
        whisper
        ggml
)

target_link_options(whisper_jni PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)

# Groundwork for using Chimera's SD/whisper helpers directly: a patched copy of the
# Chimera submodule with its CLI-only entrypoints (command_sd, command_whisper,
# command_prompt, command_tokenize, command_embed) compiled out via CHIMERA_NO_CLI,
# so those helpers can build without pulling in the CLI shell. The patch is applied
# to a copy, never to the submodule itself. Re-copying pristine sources on every
# configure keeps this idempotent: apply-patches.cmake's reverse-apply check always
# sees an unpatched tree and reapplies cleanly.
set(CHIMERA_PATCHED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.deps/chimera-patched")
file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/../chimera/src" DESTINATION "${CHIMERA_PATCHED_DIR}")
execute_process(
        COMMAND ${CMAKE_COMMAND}
                -DPATCHES=${CMAKE_CURRENT_SOURCE_DIR}/patches/chimera-no-cli.patch
                -P ${CMAKE_CURRENT_SOURCE_DIR}/apply-patches.cmake
        WORKING_DIRECTORY "${CHIMERA_PATCHED_DIR}"
        RESULT_VARIABLE _chimera_patch_rc
)
if(NOT _chimera_patch_rc EQUAL 0)
    message(FATAL_ERROR "failed to apply chimera-no-cli.patch to ${CHIMERA_PATCHED_DIR}")
endif()
unset(_chimera_patch_rc)

# Check-only: proves the patched helpers compile for Android. Nothing links this
# target. chimera_llama.cpp is left out — it needs llama.cpp's mtmd (multimodal)
# library, which this build does not produce.
add_library(chimera_helpers STATIC
        "${CHIMERA_PATCHED_DIR}/src/chimera/chimera_sd.cpp"
        "${CHIMERA_PATCHED_DIR}/src/chimera/chimera_whisper.cpp"
        "${CHIMERA_PATCHED_DIR}/src/chimera/stb_impl.cpp"
)

target_include_directories(chimera_helpers PRIVATE
        "${CHIMERA_PATCHED_DIR}/src/chimera"
        ${GGML_DIR}/include
)

target_compile_definitions(chimera_helpers PRIVATE CHIMERA_NO_CLI CHIMERA_HAS_SD CHIMERA_HAS_WHISPER)

target_compile_options(chimera_helpers PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
        -O3
)

target_link_libraries(chimera_helpers PRIVATE
        stable-diffusion
        whisper
        ggml
)
