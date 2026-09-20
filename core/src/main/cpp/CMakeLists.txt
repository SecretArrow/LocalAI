cmake_minimum_required(VERSION 3.22.1)
project(localai_runtime LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# LocalAI Runtime native inference bridge.
# Fetches a pinned llama.cpp release (see LLAMA_TAG passed from Gradle) and
# builds a thin JNI shared library used by LlamaCpuBackend.
# ---------------------------------------------------------------------------

include(FetchContent)

if(NOT LLAMA_TAG)
    set(LLAMA_TAG "b4755")
endif()

message(STATUS "Fetching llama.cpp ${LLAMA_TAG} ...")

FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz
    # No pinned hash: tag tarballs are immutable on GitHub.
)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(localai_runtime SHARED llama_bridge.cpp)

find_library(log-lib log)

target_link_libraries(localai_runtime
    PRIVATE
    llama
    ${log-lib}
)

target_compile_options(localai_runtime PRIVATE -O3 -fexceptions -frtti)
target_compile_features(localai_runtime PRIVATE cxx_std_17)
