cmake_minimum_required(VERSION 3.22)
project(quickjs_pending C)

add_library(quickjs-pending SHARED quickjs_pending.c)

find_library(log-lib log)
target_link_options(quickjs-pending PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(quickjs-pending ${log-lib} ${CMAKE_DL_LIBS})
