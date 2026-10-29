#pragma once
#include "libraw/libraw.h"
namespace loupe {
void prepareFull(const char* input, const char* output, progress_callback callback, void* context);
void validateFullCache(const char* path, int width, int height, int channels);
}
