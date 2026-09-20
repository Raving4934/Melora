cmake_minimum_required(VERSION 3.10.2)
project("quickjs-android")
add_subdirectory(./quickjs)
add_library(
        ${PROJECT_NAME}
        SHARED
        quickjs-jni.cpp
)
find_library(
        log-lib
        log)
target_link_libraries(
        ${PROJECT_NAME}
        ${log-lib}
        quickjs
)
target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
