cmake_minimum_required(VERSION 3.22.1)

project("booxin_jvm")

add_library(
    booxin_jvm
    SHARED
    jre_launcher.c
)

find_library(log-lib log)

target_link_libraries(
    booxin_jvm
    ${log-lib}
    dl
)
