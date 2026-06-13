cmake_minimum_required(VERSION 3.18.1)

project("securitymodule")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add obfuscation flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden")

# Source files
add_library(securitymodule SHARED
    security_detector.cpp
    native_obfuscation.cpp
    frida_detector.cpp
    magisk_detector.cpp
)

# Find required libraries
find_library(log-lib log)
find_library(dl-lib dl)

# Link libraries
target_link_libraries(securitymodule
    ${log-lib}
    ${dl-lib}
    android
)

# Optimization flags for release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(securitymodule PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_libraries(securitymodule PRIVATE -Wl,--gc-sections)
endif()

# Strip symbols in release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET securitymodule POST_BUILD
        COMMAND ${ANDROID_NM} $<TARGET_FILE:securitymodule> | grep " T " | cut -d' ' -f3 | sort > ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/symbols.txt
        COMMENT "Extracting exported symbols for security purposes"
    )
endif()