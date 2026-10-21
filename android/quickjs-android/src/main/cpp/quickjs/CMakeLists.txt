cmake_minimum_required(VERSION 3.10.2)
project(quickjs C)

set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/quickjs")
file(STRINGS "${ENGINE_DIR}/VERSION" CONFIG_VERSION LIMIT_COUNT 1)

add_library(
        ${PROJECT_NAME}
        SHARED
        "${ENGINE_DIR}/quickjs.c"
        "${ENGINE_DIR}/cutils.c"
        "${ENGINE_DIR}/libregexp.c"
        "${ENGINE_DIR}/libunicode.c"
        "${ENGINE_DIR}/dtoa.c"
)

target_include_directories(${PROJECT_NAME} PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_definitions(
        ${PROJECT_NAME}
        PRIVATE
        "_GNU_SOURCE"
        "CONFIG_VERSION=\"${CONFIG_VERSION}\""
)
set_target_properties(${PROJECT_NAME} PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(${PROJECT_NAME} PRIVATE m)

if(ANDROID)
    target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
endif()
