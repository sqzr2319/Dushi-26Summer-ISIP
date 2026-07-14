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
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-(blas|cuda|opencl|vulkan|metal|rpc|cann|musa|sycl|zdnn|zendnn|hexagon|virtgpu|openvino|webgpu|et|hip)/")
# 排除 KleidiAI (需要额外的 ARM 依赖), Spacemit (RISC-V) 和 AMX (x86)
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "(kleidiai/|spacemit/|amx/|llamafile/)")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX "/tests/|/examples/|/poms/|/scripts/|/out/")

# 排除非当前架构的优化代码 (针对 Android 编译)
if(ANDROID_ABI MATCHES "^arm")
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/(x86|powerpc|s390|wasm|riscv|loongarch)/")
elseif(ANDROID_ABI MATCHES "^x86")
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/(arm|powerpc|s390|wasm|riscv|loongarch)/")
else()
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "ggml-cpu/arch/")
endif()

# 5. 创建 JNI 库
add_library(
    llama-jni
    SHARED
    llama_jni.cpp
    ${LLAMA_SOURCES}
)

# 6. 链接
target_link_libraries(
    llama-jni
    ${log-lib}
    c++_shared
)

# 7. 编译选项
target_compile_options(llama-jni PRIVATE
    -O2
    -fPIC
)

# 8. 定义宏
target_compile_definitions(llama-jni PRIVATE
    GGML_BUILD=1
    GGML_SHARED=1
    GGML_USE_CPU=1
    _GNU_SOURCE
    GGML_VERSION="0.16.0"
    GGML_COMMIT="unknown"
)