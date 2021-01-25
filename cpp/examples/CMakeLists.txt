cmake_minimum_required(VERSION 3.2)
project(alternator-example)
set (CMAKE_CXX_STANDARD 11)
set (BUILD_SHARED_LIBS ON)

find_package(AWSSDK REQUIRED COMPONENTS dynamodb)

add_executable(demo demo.cpp)
target_link_libraries(demo ${AWSSDK_LINK_LIBRARIES})

