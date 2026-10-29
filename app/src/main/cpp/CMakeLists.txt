cmake_minimum_required(VERSION 3.22.1)
project(loupe_raw LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LIBRAW_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third-party/libraw/LibRaw-0.22.3.tar.gz")
file(SHA256 "${LIBRAW_ARCHIVE}" LIBRAW_SHA256)
if(NOT LIBRAW_SHA256 STREQUAL "789f3c33466210a1a0907f747a97c715d21bbf4af712f257fcbbd90111b227e4")
    message(FATAL_ERROR "LibRaw source archive checksum mismatch")
endif()
set(LIBRAW_ROOT "${CMAKE_CURRENT_BINARY_DIR}/LibRaw-0.22.3")
if(NOT EXISTS "${LIBRAW_ROOT}/libraw/libraw.h")
    file(ARCHIVE_EXTRACT INPUT "${LIBRAW_ARCHIVE}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif()
set(LIBRAW_SOURCES
    "${LIBRAW_ROOT}/src/decoders/canon_600.cpp"
    "${LIBRAW_ROOT}/src/decoders/crx.cpp"
    "${LIBRAW_ROOT}/src/decoders/decoders_dcraw.cpp"
    "${LIBRAW_ROOT}/src/decoders/decoders_libraw.cpp"
    "${LIBRAW_ROOT}/src/decoders/decoders_libraw_dcrdefs.cpp"
    "${LIBRAW_ROOT}/src/decoders/dng.cpp"
    "${LIBRAW_ROOT}/src/decoders/fp_dng.cpp"
    "${LIBRAW_ROOT}/src/decoders/fuji_compressed.cpp"
    "${LIBRAW_ROOT}/src/decoders/generic.cpp"
    "${LIBRAW_ROOT}/src/decoders/kodak_decoders.cpp"
    "${LIBRAW_ROOT}/src/decoders/load_mfbacks.cpp"
    "${LIBRAW_ROOT}/src/decoders/olympus14.cpp"
    "${LIBRAW_ROOT}/src/decoders/pana8.cpp"
    "${LIBRAW_ROOT}/src/decoders/smal.cpp"
    "${LIBRAW_ROOT}/src/decoders/sonycc.cpp"
    "${LIBRAW_ROOT}/src/decoders/unpack.cpp"
    "${LIBRAW_ROOT}/src/decoders/unpack_thumb.cpp"
    "${LIBRAW_ROOT}/src/decompressors/losslessjpeg.cpp"
    "${LIBRAW_ROOT}/src/demosaic/aahd_demosaic.cpp"
    "${LIBRAW_ROOT}/src/demosaic/ahd_demosaic.cpp"
    "${LIBRAW_ROOT}/src/demosaic/dcb_demosaic.cpp"
    "${LIBRAW_ROOT}/src/demosaic/dht_demosaic.cpp"
    "${LIBRAW_ROOT}/src/demosaic/misc_demosaic.cpp"
    "${LIBRAW_ROOT}/src/demosaic/xtrans_demosaic.cpp"
    "${LIBRAW_ROOT}/src/integration/dngsdk_glue.cpp"
    "${LIBRAW_ROOT}/src/integration/rawspeed_glue.cpp"
    "${LIBRAW_ROOT}/src/libraw_c_api.cpp"
    "${LIBRAW_ROOT}/src/libraw_datastream.cpp"
    "${LIBRAW_ROOT}/src/metadata/adobepano.cpp"
    "${LIBRAW_ROOT}/src/metadata/canon.cpp"
    "${LIBRAW_ROOT}/src/metadata/ciff.cpp"
    "${LIBRAW_ROOT}/src/metadata/cr3_parser.cpp"
    "${LIBRAW_ROOT}/src/metadata/epson.cpp"
    "${LIBRAW_ROOT}/src/metadata/exif_gps.cpp"
    "${LIBRAW_ROOT}/src/metadata/fuji.cpp"
    "${LIBRAW_ROOT}/src/metadata/hasselblad_model.cpp"
    "${LIBRAW_ROOT}/src/metadata/identify.cpp"
    "${LIBRAW_ROOT}/src/metadata/identify_tools.cpp"
    "${LIBRAW_ROOT}/src/metadata/kodak.cpp"
    "${LIBRAW_ROOT}/src/metadata/leica.cpp"
    "${LIBRAW_ROOT}/src/metadata/makernotes.cpp"
    "${LIBRAW_ROOT}/src/metadata/mediumformat.cpp"
    "${LIBRAW_ROOT}/src/metadata/minolta.cpp"
    "${LIBRAW_ROOT}/src/metadata/misc_parsers.cpp"
    "${LIBRAW_ROOT}/src/metadata/nikon.cpp"
    "${LIBRAW_ROOT}/src/metadata/normalize_model.cpp"
    "${LIBRAW_ROOT}/src/metadata/olympus.cpp"
    "${LIBRAW_ROOT}/src/metadata/p1.cpp"
    "${LIBRAW_ROOT}/src/metadata/pentax.cpp"
    "${LIBRAW_ROOT}/src/metadata/samsung.cpp"
    "${LIBRAW_ROOT}/src/metadata/sony.cpp"
    "${LIBRAW_ROOT}/src/metadata/tiff.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/aspect_ratio.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/dcraw_process.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/mem_image.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/postprocessing_aux.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/postprocessing_utils.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/postprocessing_utils_dcrdefs.cpp"
    "${LIBRAW_ROOT}/src/preprocessing/ext_preprocess.cpp"
    "${LIBRAW_ROOT}/src/preprocessing/raw2image.cpp"
    "${LIBRAW_ROOT}/src/preprocessing/subtract_black.cpp"
    "${LIBRAW_ROOT}/src/tables/cameralist.cpp"
    "${LIBRAW_ROOT}/src/tables/colorconst.cpp"
    "${LIBRAW_ROOT}/src/tables/colordata.cpp"
    "${LIBRAW_ROOT}/src/tables/wblists.cpp"
    "${LIBRAW_ROOT}/src/utils/curves.cpp"
    "${LIBRAW_ROOT}/src/utils/decoder_info.cpp"
    "${LIBRAW_ROOT}/src/utils/init_close_utils.cpp"
    "${LIBRAW_ROOT}/src/utils/open.cpp"
    "${LIBRAW_ROOT}/src/utils/phaseone_processing.cpp"
    "${LIBRAW_ROOT}/src/utils/read_utils.cpp"
    "${LIBRAW_ROOT}/src/utils/thumb_utils.cpp"
    "${LIBRAW_ROOT}/src/utils/utils_dcraw.cpp"
    "${LIBRAW_ROOT}/src/utils/utils_libraw.cpp"
    "${LIBRAW_ROOT}/src/write/apply_profile.cpp"
    "${LIBRAW_ROOT}/src/write/file_write.cpp"
    "${LIBRAW_ROOT}/src/write/tiff_writer.cpp"
    "${LIBRAW_ROOT}/src/x3f/x3f_parse_process.cpp"
    "${LIBRAW_ROOT}/src/x3f/x3f_utils_patched.cpp"
)
add_library(loupe_libraw STATIC ${LIBRAW_SOURCES})
set_target_properties(loupe_libraw PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(loupe_libraw PUBLIC "${LIBRAW_ROOT}")
target_compile_definitions(loupe_libraw PRIVATE NO_JPEG NO_JASPER USE_ZLIB LIBRAW_NO_IOSTREAMS_DATASTREAM)
target_compile_options(loupe_libraw PRIVATE -w)
target_link_libraries(loupe_libraw PUBLIC z)
add_library(loupe_raw_core STATIC raw_decode.cpp raw_full.cpp)
set_target_properties(loupe_raw_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(loupe_raw_core PUBLIC loupe_libraw)
set(JPEG_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third-party/libjpeg-turbo/libjpeg-turbo-3.2.1.tar.gz")
file(SHA256 "${JPEG_ARCHIVE}" JPEG_SHA256)
if(NOT JPEG_SHA256 STREQUAL "c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f")
    message(FATAL_ERROR "libjpeg-turbo source archive checksum mismatch")
endif()
set(JPEG_ROOT "${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-3.2.1")
if(NOT EXISTS "${JPEG_ROOT}/CMakeLists.txt")
    file(ARCHIVE_EXTRACT INPUT "${JPEG_ARCHIVE}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif()
include(ExternalProject)
set(JPEG_BINARY "${CMAKE_CURRENT_BINARY_DIR}/jpeg-build")
set(JPEG_CMAKE_ARGS
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF
    -DWITH_TOOLS=OFF -DWITH_TESTS=OFF -DWITH_ARITH_ENC=OFF -DWITH_ARITH_DEC=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER})
if(ANDROID)
    list(APPEND JPEG_CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
elseif(DEFINED WITH_SIMD)
    list(APPEND JPEG_CMAKE_ARGS -DWITH_SIMD=${WITH_SIMD})
endif()
ExternalProject_Add(loupe_jpeg_build SOURCE_DIR "${JPEG_ROOT}" BINARY_DIR "${JPEG_BINARY}"
    CMAKE_ARGS ${JPEG_CMAKE_ARGS} INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${JPEG_BINARY}/libjpeg.a")
add_library(loupe_jpeg_static STATIC IMPORTED)
set_target_properties(loupe_jpeg_static PROPERTIES IMPORTED_LOCATION "${JPEG_BINARY}/libjpeg.a")
add_dependencies(loupe_jpeg_static loupe_jpeg_build)
add_library(loupe_jpeg_core STATIC jpeg_encoder.cpp)
set_target_properties(loupe_jpeg_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(loupe_jpeg_core PRIVATE "${JPEG_ROOT}/src" "${JPEG_BINARY}")
target_link_libraries(loupe_jpeg_core PUBLIC loupe_jpeg_static)
if(ANDROID)
    add_library(loupe_raw SHARED native_raw.cpp native_jpeg.cpp)
    target_link_libraries(loupe_raw PRIVATE loupe_raw_core loupe_jpeg_core)
    target_link_options(loupe_raw PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
else()
    add_executable(full_pipeline_check full_pipeline_check.cpp)
    target_include_directories(full_pipeline_check PRIVATE "${JPEG_ROOT}/src" "${JPEG_BINARY}")
    target_link_libraries(full_pipeline_check PRIVATE loupe_raw_core loupe_jpeg_core)
    add_executable(raw_decode_check raw_decode_check.cpp)
    target_link_libraries(raw_decode_check PRIVATE loupe_raw_core)
endif()
