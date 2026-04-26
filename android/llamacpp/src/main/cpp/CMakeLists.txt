cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
set(GGML_VULKAN_EXTRA_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party)
# The historical Android integration pinned a llama.cpp revision where mtmd's
# CMake expects LLAMA_INSTALL_VERSION to already be defined.
if(NOT DEFINED LLAMA_INSTALL_VERSION OR LLAMA_INSTALL_VERSION STREQUAL "")
    set(LLAMA_INSTALL_VERSION 0.0.0)
endif()
add_subdirectory(${LLAMA_SRC} build-llama)
add_subdirectory(${LLAMA_SRC}/tools/mtmd build-mtmd)

if(TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
            ${CMAKE_CURRENT_LIST_DIR}/third_party)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp
        multimodal.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${CMAKE_CURRENT_LIST_DIR}/third_party
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        mtmd
        android
        log)
