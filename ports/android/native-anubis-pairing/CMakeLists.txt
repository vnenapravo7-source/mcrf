cmake_minimum_required(VERSION 3.22)
project(anubis_adb_pairing LANGUAGES C CXX)

# The producer supplies a restored, verified copy of the AWS-LC source.
if(NOT IS_DIRECTORY "${ANUBIS_AWS_LC_SOURCE}/include/openssl")
    message(FATAL_ERROR "ANUBIS_AWS_LC_SOURCE must point to the pinned AWS-LC source")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_LIBSSL OFF CACHE BOOL "" FORCE)
set(BUILD_TOOL OFF CACHE BOOL "" FORCE)
set(DISABLE_GO ON CACHE BOOL "" FORCE)
set(DISABLE_PERL ON CACHE BOOL "" FORCE)
add_subdirectory("${ANUBIS_AWS_LC_SOURCE}" aws-lc EXCLUDE_FROM_ALL)

add_library(anubis_pairing_auth STATIC pairing_auth.cpp)
target_compile_features(anubis_pairing_auth PUBLIC cxx_std_17)
target_link_libraries(anubis_pairing_auth PUBLIC crypto)
set_target_properties(anubis_pairing_auth PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(mcrf_anubis_pairing SHARED pairing_jni.cpp)
    target_link_libraries(mcrf_anubis_pairing PRIVATE anubis_pairing_auth)
    set_target_properties(mcrf_anubis_pairing PROPERTIES CXX_VISIBILITY_PRESET hidden)
    target_link_options(mcrf_anubis_pairing PRIVATE
        "LINKER:--exclude-libs,ALL" "LINKER:-z,max-page-size=16384")
endif()

add_executable(anubis_pairing_auth_test pairing_auth_test.cpp)
target_link_libraries(anubis_pairing_auth_test PRIVATE anubis_pairing_auth)
