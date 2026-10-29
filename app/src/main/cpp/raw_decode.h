#pragma once
#include <memory>
#include <string>
#include "libraw/libraw.h"

namespace loupe {
// Per-request ownership. LibRaw applies EXIF orientation in make_mem_image once.
class RawImage {
public:
    RawImage(const char* path, int maxSide, progress_callback callback = nullptr, void* context = nullptr);
    ~RawImage();
    RawImage(const RawImage&) = delete;
    RawImage& operator=(const RawImage&) = delete;
    int width() const { return width_; }
    int height() const { return height_; }
    int originalWidth() const { return originalWidth_; }
    int originalHeight() const { return originalHeight_; }
    void write(float* output) const;
private:
    std::unique_ptr<LibRaw> raw_;
    libraw_processed_image_t* image_ = nullptr;
    int width_ = 0, height_ = 0;
    int originalWidth_ = 0, originalHeight_ = 0;
};
}
