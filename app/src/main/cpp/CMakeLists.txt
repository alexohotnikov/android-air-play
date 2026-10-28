cmake_minimum_required(VERSION 3.22)
project(airplaytv C)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(UX ${TP}/UxPlay/lib)

# libplist 2.6, C part only.
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${TP}/libplist/include
        PRIVATE ${TP}/libplist/src ${TP}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE PACKAGE_VERSION="2.6.0" HAVE_STRNDUP)
target_compile_options(plist PRIVATE -w)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
        IMPORTED_LOCATION ${TP}/openssl-android/${ANDROID_ABI}/lib/libcrypto.a
        INTERFACE_INCLUDE_DIRECTORIES ${TP}/openssl-android/${ANDROID_ABI}/include)

# UxPlay's AirPlay core with its own minimal mDNS responder (lib/mdnsd); its GStreamer renderers are replaced
# by renderer.c.
file(GLOB UX_SRC ${UX}/*.c ${UX}/playfair/*.c ${UX}/llhttp/*.c ${UX}/mdnsd/*.c)
add_library(airplay STATIC ${UX_SRC})
target_include_directories(airplay PUBLIC ${UX} ${UX}/playfair ${UX}/llhttp ${UX}/mdnsd)
target_compile_definitions(airplay PUBLIC STANDALONE TARGET_POSIX _LINUX _REENTRANT NOHOLD
        PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -O2 -w)
target_link_libraries(airplay PUBLIC plist crypto)
set_source_files_properties(${UX}/mdnsd/dnssd_mdnsd.c PROPERTIES COMPILE_FLAGS
        "-Dgethostname=airplaytv_gethostname -include stddef.h -include ${CMAKE_CURRENT_SOURCE_DIR}/hostname.h")

add_library(airplaytv SHARED airplay_jni.c renderer.c)
target_compile_options(airplaytv PRIVATE -O2 -Wall)
target_link_libraries(airplaytv airplay mediandk aaudio android log)
