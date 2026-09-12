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
# 该文件形状上也是 *.comp.cpp，但它不是 shader，下面单独作为 VK_ARR_DMMV_SRC 加入
list(FILTER VK_SHADER_SOURCES EXCLUDE REGEX "arr_dmmv_tables\\.comp\\.cpp$")
list(LENGTH VK_SHADER_SOURCES VK_SHADER_SOURCE_COUNT)

# Vulkan 生成的头文件路径（ggml-vulkan.cpp 以 "ggml-vulkan-shaders.hpp" 相对包含）
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/vulkan-shaders)

# ---------------------------------------------------------------
# Vulkan 头文件（vulkan.hpp / vulkan_core.h / spirv.hpp）
#
# NDK sysroot 自带的 vulkan_core.h 只有 VK_HEADER_VERSION 275，缺少本版本
# ggml-vulkan.cpp 需要的扩展定义（VK_KHR_shader_bfloat16、
# VK_NV_cooperative_matrix2 等），所以必须使用 Khronos 官方头文件。两种来源：
#   1) 环境变量 VULKAN_SDK 指向的 LunarG Vulkan SDK（含 glslc，推荐）
#   2) 项目内 third_party/khronos 的固定版本头文件（离线可用）
# ---------------------------------------------------------------
set(KHRONOS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/khronos")

set(VULKAN_INCLUDE_DIRS "")
set(VULKAN_INCLUDE_SOURCE "")

if(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/Include/vulkan/vulkan.hpp")
    list(APPEND VULKAN_INCLUDE_DIRS "$ENV{VULKAN_SDK}/Include")
    set(VULKAN_INCLUDE_SOURCE "VULKAN_SDK=$ENV{VULKAN_SDK}")
endif()

# 项目内固定版本头文件：Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers。
# Vulkan-Hpp 放在最前，确保 <vulkan/vulkan.hpp> 与其配套的
# vulkan_structs.hpp / vulkan_handles.hpp 来自同一版本。
if(EXISTS "${KHRONOS_ROOT}/Vulkan-Hpp/vulkan/vulkan.hpp")
    list(APPEND VULKAN_INCLUDE_DIRS
        "${KHRONOS_ROOT}/Vulkan-Hpp"
        "${KHRONOS_ROOT}/Vulkan-Headers/include"
        "${KHRONOS_ROOT}/SPIRV-Headers/include")
    if(VULKAN_INCLUDE_SOURCE STREQUAL "")
        set(VULKAN_INCLUDE_SOURCE "third_party/khronos")
    else()
        set(VULKAN_INCLUDE_SOURCE "${VULKAN_INCLUDE_SOURCE} + third_party/khronos")
    endif()
endif()

if(VULKAN_INCLUDE_DIRS STREQUAL "")
    message(FATAL_ERROR
        "找不到 Vulkan C++ 头文件（vulkan/vulkan.hpp）。请任选其一：\n"
        "  1) 安装 LunarG Vulkan SDK 并设置环境变量 VULKAN_SDK；\n"
        "  2) 在 ${KHRONOS_ROOT} 下放置 Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers。")
endif()
list(REMOVE_DUPLICATES VULKAN_INCLUDE_DIRS)
include_directories(${VULKAN_INCLUDE_DIRS})
message(STATUS "Vulkan headers from: ${VULKAN_INCLUDE_SOURCE}")

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
if(NOT EXISTS "${VULKAN_LIB}")
    message(FATAL_ERROR "找不到 Vulkan 链接库: ${VULKAN_LIB}")
endif()

# Vulkan 后端实现（此前被下面的 EXCLUDE 过滤器连同 hexagon 一起排除了，
# 导致 GGML_USE_VULKAN 虽有定义却没有任何后端实现被编译）
set(VK_BACKEND_SRC "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
if(NOT EXISTS "${VK_BACKEND_SRC}")
    message(FATAL_ERROR "找不到 Vulkan 后端源码: ${VK_BACKEND_SRC}")
endif()
if(VK_SHADER_SOURCE_COUNT EQUAL 0)
    message(FATAL_ERROR "找不到预生成的 Vulkan shader 源码: ${VK_SHADER_DIR}/*.comp.cpp")
endif()

# arr_dmmv_* 聚合表。
#
# 这些不是 shader，而是把同一 matmul 变体的三个实现（默认 / subgroup /
# subgroup_no_shmem）打包成 [3] 数组，运行时按设备 subgroup 能力挑选。
# 上游生成器只在 basename(input_filepath) == "mul_mat_vec.comp" 时把它们写进聚合
# src，而那条路径同时又在逐个尝试打开 .spv、遇到不存在的就 continue —— 结果这 75 组
# 表一条都没落盘（vulkan-shaders-gen.cpp:1235-1275）。没有它们，libllama-jni.so
# 一定链接失败：undefined symbol arr_dmmv_*。
#
# 该文件由 shader-cpps/gen_arr_dmmv_tables.ps1 按生成器的同一模板生成，只补那些
# 三个基础变体确实都已存在的表。
set(VK_ARR_DMMV_SRC "${VK_SHADER_DIR}/arr_dmmv_tables.comp.cpp")
if(NOT EXISTS "${VK_ARR_DMMV_SRC}")
    message(FATAL_ERROR
        "缺少 ${VK_ARR_DMMV_SRC}。请运行 "
        "app/src/main/cpp/vulkan-shaders/shader-cpps/gen_arr_dmmv_tables.ps1 重新生成。")
endif()
message(STATUS "Vulkan backend: ggml-vulkan.cpp + ${VK_SHADER_SOURCE_COUNT} shaders + arr_dmmv tables")

# 7. 创建 JNI 库（含 mtmd + Vulkan）
add_library(
    llama-jni
    SHARED
    llama_jni.cpp
    ${LLAMA_SOURCES}
    ${MTMD_CORE_SOURCES}
    ${MTMD_MODEL_SOURCES}
    ${VK_BACKEND_SRC}
    ${VK_ARR_DMMV_SRC}
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

# 10b. Vulkan shader 特性宏 —— 必须与 vulkan-shaders/ 下预生成 shader 的那次生成
# 过程保持一致，否则 ggml-vulkan.cpp 会引用不存在的 *_data 符号而链接失败。
#
# 实测结论（核对 ggml-vulkan-shaders.hpp 的 1344 个声明与 shader-cpps/*.comp.cpp
# 的 1344 个定义，完全一一对应）：这批 shader 是在所有可选扩展均不可用时生成的，
# 具体证据是 coopmat / coopmat2 / integer_dot / bfloat16 / float_e2m1 / float_e4m3
# 的符号数均为 0，对应的 .comp.cpp（如 flash_attn_cm1.comp.cpp、mul_mmq.comp.cpp）
# 都是只有一行 #include 的空壳。
#
# 因此下面这些宏一律不定义。将来若用 Vulkan SDK + glslc 重新生成 shader，
# 应把该次生成时 glslc 支持的扩展对应的宏同步加到这里：
#   GGML_VULKAN_COOPMAT_GLSLC_SUPPORT
#   GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT
#   GGML_VULKAN_COOPMAT2_DECODE_VECTOR_GLSLC_SUPPORT
#   GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT
#   GGML_VULKAN_BFLOAT16_GLSLC_SUPPORT
#   GGML_VULKAN_FLOAT_E2M1_GLSLC_SUPPORT
#   GGML_VULKAN_FLOAT_E4M3_GLSLC_SUPPORT