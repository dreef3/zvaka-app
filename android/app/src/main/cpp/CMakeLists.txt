cmake_minimum_required(VERSION 3.22.1)

project(smolvlm_poc)

add_library(
    smolvlm_poc
    SHARED
    smolvlm_poc.cpp
)

find_library(log-lib log)

target_link_libraries(
    smolvlm_poc
    ${log-lib}
)
