cmake_minimum_required(VERSION 3.22.1)
project("nativebridge")

add_library(nativebridge SHARED NativeProcess.c)

target_include_directories(nativebridge PRIVATE .)
target_link_libraries(nativebridge log)
