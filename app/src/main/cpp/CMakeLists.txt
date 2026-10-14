cmake_minimum_required(VERSION 3.22.1)
project("jarvis_llm")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always build llama.cpp optimized, even for the debug APK. The NDK debug config
# would otherwise compile with -O0 and generation becomes unusably slow.
set(CMAKE_C_FLAGS_DEBUG     "-O3 -DNDEBUG -g")
set(CMAKE_CXX_FLAGS_DEBUG   "-O3 -DNDEBUG -g")
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# Galaxy A15 (Cortex-A76/A55) supports the ARMv8.2 dot-product instructions,
# which speed up Q4_K_M matmuls. Turn OFF for very old arm64 phones.
option(JARVIS_ARM64_DOTPROD "Enable ARMv8.2-A dot-product instructions" ON)

set(ARCH_FLAGS "")
if(ANDROID_ABI STREQUAL "arm64-v8a" AND JARVIS_ARM64_DOTPROD)
    set(ARCH_FLAGS "-march=armv8.2-a+dotprod")
endif()

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3 -DNDEBUG -fPIC ${ARCH_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fPIC ${ARCH_FLAGS}")

# CPU-only llama.cpp. The vendored copy in ./llama is trimmed to what this
# configuration needs (no GPU backends, tools, tests or examples).
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)
set(GGML_BLAS    OFF CACHE BOOL "" FORCE)
set(GGML_METAL   OFF CACHE BOOL "" FORCE)
set(GGML_CANN    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama)
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

add_library(jarvis_llm SHARED jarvis_llm.cpp)

find_library(log-lib log)

target_include_directories(jarvis_llm PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(jarvis_llm
    ${log-lib}
    llama
)
