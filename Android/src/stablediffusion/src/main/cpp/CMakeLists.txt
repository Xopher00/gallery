cmake_minimum_required(VERSION 3.22.1)
project("stablediffusion_jni")

set(SD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../stable-diffusion.cpp")

# Force static builds to avoid symbol conflicts with llama.cpp's ggml
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
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
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${SD_ROOT} sd_build)

# JNI shared library: everything statically linked, all internal symbols hidden
add_library(stablediffusion SHARED SDInference.cpp)

target_include_directories(stablediffusion PRIVATE
    ${SD_ROOT}/include
)

target_compile_options(stablediffusion PRIVATE
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
