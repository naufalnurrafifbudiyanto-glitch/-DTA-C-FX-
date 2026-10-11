cmake_minimum_required(VERSION 3.22.1)

project(dtac_fx)

add_library(
    dtac_fx
    SHARED
    native_audio.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    dtac_fx
    ${log-lib}
)
