cmake_minimum_required(VERSION 3.22.1)
project(kosubwhisper LANGUAGES C CXX)

include(FetchContent)

# whisper.cpp 소스는 빌드할 때 GitHub에서 받아 온다 (태그 고정)
set(KOSUB_WHISPER_TAG "v1.7.5" CACHE STRING "whisper.cpp git tag")
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        ${KOSUB_WHISPER_TAG}
    GIT_SHALLOW    TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whisper)

# 디버그 빌드에서도 음성 인식은 최적화해서 빌드한다 (그렇지 않으면 매우 느림)
foreach(t whisper ggml ggml-base ggml-cpu)
    if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O3 -DNDEBUG)
    endif()
endforeach()

add_library(kosubwhisper SHARED whisper_jni.cpp)
target_compile_options(kosubwhisper PRIVATE -O3 -DNDEBUG)
target_link_libraries(kosubwhisper PRIVATE whisper android log)
