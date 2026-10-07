cmake_minimum_required(VERSION 3.22)
project(euno_llama LANGUAGES C CXX)

# Fonte oficial do llama.cpp (ggml-org, MIT) na tag v0.5.0, baixada pelo CI em src/main/cpp/llama.cpp (fora do git).
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "Fonte do llama.cpp ausente em ${LLAMA_SRC}: git clone --depth 1 --branch v0.5.0 https://github.com/ggml-org/llama.cpp ${LLAMA_SRC}")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod+fp16 roda em qualquer arm64 moderno (Redmi Note 13 Pro+: Dimensity 7200-Ultra); i8mm não é exigido.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(euno_llama SHARED euno_llama_jni.cpp)
target_include_directories(euno_llama PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(euno_llama PRIVATE llama ggml android log)
