cmake_minimum_required(VERSION 3.18.1)
project("llama-jni")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# 1. llama.cpp 源码路径
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    message(FATAL_ERROR "llama.h not found at ${LLAMA_CPP_DIR}/include/llama.h")
endif()

message(STATUS "Found llama.cpp at: ${LLAMA_CPP_DIR}")

# ============================================
# 2. 添加所有需要的头文件路径（关键！）
# ============================================
include_directories(${LLAMA_CPP_DIR}/include)      # llama.h 在这里
include_directories(${LLAMA_CPP_DIR}/ggml/include) # ggml.h 在这里  ← 你缺这个！
include_directories(${LLAMA_CPP_DIR}/ggml/src)     # ggml 内部头文件
include_directories(${LLAMA_CPP_DIR}/src)          # llama.cpp 内部头文件

# 3. 如果有 ggml-cpu 的额外头文件
if(EXISTS "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu")
    include_directories(${LLAMA_CPP_DIR}/ggml/src/ggml-cpu)
endif()

# 4. 收集 llama.cpp 源文件
file(GLOB_RECURSE LLAMA_SOURCES
    "${LLAMA_CPP_DIR}/src/*.cpp"
    "${LLAMA_CPP_DIR}/src/*.c"
    "${LLAMA_CPP_DIR}/ggml/src/*.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/*.c"
)

# 排除不需要的后端（如 BLAS, CUDA 等）以及测试文件
# Android NDK 默认不包含 cblas.h，因此必须排除 ggml-blas
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-(blas|cuda|opencl|metal|rpc|cann|musa|sycl|zdnn|zendnn|hexagon|virtgpu|openvino|webgpu|et|hip)/")
# 排除 KleidiAI (需要额外的 ARM 依赖), Spacemit (RISC-V) 和 AMX (x86)
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "(kleidiai/|spacemit/|amx/|llamafile/)")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "/tests/|/examples/|/poms/|/scripts/|/out/|/build/shader-cpps/|/vulkan-shaders/")

# 排除非当前架构的优化代码 (针对 Android 编译)
if(ANDROID_ABI MATCHES "^arm")
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/(x86|powerpc|s390|wasm|riscv|loongarch)/")
elseif(ANDROID_ABI MATCHES "^x86")
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/(arm|powerpc|s390|wasm|riscv|loongarch)/")
else()
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/")
endif()

# 5. 添加 mtmd 多模态支持的头文件路径
include_directories(${LLAMA_CPP_DIR}/tools/mtmd)

# 5b. mtmd 依赖的第三方头文件
if(EXISTS "${LLAMA_CPP_DIR}/vendor/miniaudio")
    include_directories(${LLAMA_CPP_DIR}/vendor)
endif()

# 6. 收集 mtmd 源文件
file(GLOB_RECURSE MTMD_CORE_SOURCES
    "${LLAMA_CPP_DIR}/tools/mtmd/mtmd.cpp"
    "${LLAMA_CPP_DIR}/tools/mtmd/mtmd-image.cpp"
    "${LLAMA_CPP_DIR}/tools/mtmd/mtmd-audio.cpp"
    "${LLAMA_CPP_DIR}/tools/mtmd/mtmd-helper.cpp"
    "${LLAMA_CPP_DIR}/tools/mtmd/clip.cpp"
    "${LLAMA_CPP_DIR}/tools/mtmd/clip-graph.cpp"
)
file(GLOB_RECURSE MTMD_MODEL_SOURCES
    "${LLAMA_CPP_DIR}/tools/mtmd/models/*.cpp"
)

# ============================================
# 6b. Vulkan GPU 后端
# ============================================
# 预生成的 shader C++ 文件路径（在项目目录中，不在子模块内）
set(VK_SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-shaders/shader-cpps")
file(GLOB VK_SHADER_SOURCES "${VK_SHADER_DIR}/*.comp.cpp")

# Vulkan 生成的头文件路径
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/vulkan-shaders)

# Vulkan C++ header (vulkan.hpp) 来自 Vulkan SDK
# 优先使用 VULKAN_SDK 环境变量，否则用默认路径
set(VK_SDK_ROOT "")
if(DEFINED ENV{VULKAN_SDK})
    set(VK_SDK_ROOT "$ENV{VULKAN_SDK}")
elseif(EXISTS "C:/VulkanSDK/1.4.350.0")
    set(VK_SDK_ROOT "C:/VulkanSDK/1.4.350.0")
endif()
if(VK_SDK_ROOT)
    include_directories(${VK_SDK_ROOT}/Include)
    message(STATUS "Vulkan SDK headers from: ${VK_SDK_ROOT}/Include")
endif()

# 链接 NDK 的高版本 libvulkan（API 29，含 Vulkan 1.1+ 符号）
# 运行时 libvulkan.so 从设备加载，不受 stub 版本限制
# Android NDK 的 CMake 工具链默认使用 target API 的 sysroot，这里手动指向 API 29
if(EXISTS "${CMAKE_SYSROOT}/../usr/lib/aarch64-linux-android/29/libvulkan.so")
    set(VULKAN_LIB "${CMAKE_SYSROOT}/../usr/lib/aarch64-linux-android/29/libvulkan.so")
elseif(ANDROID_NDK)
    set(VULKAN_LIB "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/29/libvulkan.so")
else()
    find_library(VULKAN_LIB vulkan)
endif()
message(STATUS "Vulkan lib: ${VULKAN_LIB}")

# 7. 创建 JNI 库（含 mtmd + Vulkan）
add_library(
    llama-jni
    SHARED
    llama_jni.cpp
    ${LLAMA_SOURCES}
    ${MTMD_CORE_SOURCES}
    ${MTMD_MODEL_SOURCES}
    ${VK_SHADER_SOURCES}
)

# 7b. 对 Vulkan shader 生成的大 C++ 文件降低优化等级（避免编译器 OOM）
# COMPILE_FLAGS 会追加到该源文件的编译选项中，覆盖全局 -O2
foreach(VK_SHADER_SRC ${VK_SHADER_SOURCES})
    set_source_files_properties(${VK_SHADER_SRC} PROPERTIES COMPILE_FLAGS "-O0")
endforeach()

# 8. 链接
target_link_libraries(
    llama-jni
    ${log-lib}
    ${VULKAN_LIB}
    c++_shared
)

# 9. 编译选项
target_compile_options(llama-jni PRIVATE
    -O2
    -fPIC
)

# 10. 定义宏
target_compile_definitions(llama-jni PRIVATE
    GGML_BUILD=1
    GGML_SHARED=1
    GGML_USE_CPU=1
    GGML_USE_VULKAN=1
    _GNU_SOURCE
    GGML_VERSION="0.16.0"
    GGML_COMMIT="unknown"
)