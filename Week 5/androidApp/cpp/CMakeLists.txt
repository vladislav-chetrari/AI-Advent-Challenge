# Сборка libllama.so: llama.cpp (v0.6.0, CPU) + наш JNI-мост bridge.cpp.
# Исходники llama.cpp: androidApp/cpp/llama.cpp (клонируются скриптом tools/build-llama.sh).
# Конфигурация (пример для эмулятора x86_64):
#   cmake -S androidApp/cpp -B build/llama-x86_64 \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-26 \
#     -DCMAKE_BUILD_TYPE=Release -G Ninja
#   cmake --build build/llama-x86_64 --config Release
cmake_minimum_required(VERSION 3.22)
project(llama-bridge)

set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "Нет ${LLAMA_SRC}/CMakeLists.txt — сначала: sh tools/build-llama.sh <abi>")
endif()

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} llama.cpp)

add_library(bridge SHARED bridge.cpp)
target_link_libraries(bridge PRIVATE llama log)
target_include_directories(bridge PRIVATE ${LLAMA_SRC}/include)
# Имя отличается от libllama.so самой llama.cpp (та линкуется shared отдельно).
set_target_properties(bridge PROPERTIES OUTPUT_NAME task2bridge)
