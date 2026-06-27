cmake_minimum_required(VERSION 3.22.1)
project("security_check")

add_library(security_check SHARED security_check.c)

find_library(log-lib log)
target_link_libraries(security_check ${log-lib})
