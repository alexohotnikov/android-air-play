cmake_minimum_required(VERSION 3.22)
project(airplaytv C CXX)

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

# Apple's ALAC decoder: the audio of music-only AirPlay (ct=2), which Android has no decoder for.
set(ALAC ${TP}/alac/codec)
add_library(alac STATIC ${ALAC}/ALACDecoder.cpp ${ALAC}/ALACBitUtilities.c ${ALAC}/EndianPortable.c
        ${ALAC}/ag_dec.c ${ALAC}/dp_dec.c ${ALAC}/matrix_dec.c)
target_include_directories(alac PUBLIC ${ALAC})
# Its EndianPortable.c knows little-endian only on x86 and Windows: ARM would read the magic cookie byte-swapped
# and Init() refuses it.
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -O2 -w)

add_library(airplaytv SHARED airplay_jni.c renderer.c alac_bridge.cpp)
target_compile_options(airplaytv PRIVATE -O2 -Wall)
target_link_libraries(airplaytv airplay alac mediandk aaudio android log)
