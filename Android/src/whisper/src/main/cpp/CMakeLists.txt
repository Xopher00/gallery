cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni")

set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp")

set(BUILD_SHARED_LIBS          OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL               OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2               OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML             OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(GGML_METAL                 OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                  OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN                OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL                OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE             OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} whisper_build)

add_library(whisper_jni SHARED WhisperInference.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_ROOT}
    ${WHISPER_ROOT}/include
    ${WHISPER_ROOT}/ggml/include
)

target_compile_options(whisper_jni PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -O3
)

target_link_libraries(whisper_jni android log whisper ggml)

target_link_options(whisper_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
