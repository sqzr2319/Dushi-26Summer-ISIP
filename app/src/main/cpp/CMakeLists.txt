cmake_minimum_required(VERSION 3.18.1)

project("llama-jni")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 查找 Android log 库
find_library(log-lib log)

# llama.cpp 路径 (绝对路径)
set(LLAMA_CPP_DIR "C:/Files/GitHub/Projects/llama.cpp")

# 添加 llama.cpp 头文件路径
include_directories(${LLAMA_CPP_DIR}/include)
include_directories(${LLAMA_CPP_DIR}/ggml/include)
include_directories(${LLAMA_CPP_DIR}/src)

# 创建 JNI 库
add_library(
    llama-jni
    SHARED
    llama_jni.cpp
)

# 链接库
target_link_libraries(
    llama-jni
    ${log-lib}
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so
)
