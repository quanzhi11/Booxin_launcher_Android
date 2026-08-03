cmake_minimum_required(VERSION 3.22.1)

project("booxin_native")

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

add_library(
    booxin_jvm
    SHARED
    jre_launcher.c
)

target_link_libraries(
    booxin_jvm
    ${log-lib}
    dl
)

add_library(
    booxin_bridge
    SHARED
    booxin_bridge/booxin_environ.c
    booxin_bridge/booxin_input.c
    booxin_bridge/booxin_egl.c
    booxin_bridge/booxin_jni.c
)

target_include_directories(booxin_bridge PRIVATE booxin_bridge)

target_link_libraries(
    booxin_bridge
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    dl
)
