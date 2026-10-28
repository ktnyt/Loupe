#include "raw_decode.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <sys/stat.h>

namespace {
constexpr uint64_t MAX_FILE_BYTES = 256ULL * 1024 * 1024;
constexpr uint64_t MAX_PIXELS = 80000000;
constexpr unsigned MAX_DIMENSION = 16384;
void silenceError(void*, const char*, const INT64) {}
void check(int error) {
    if (error == LIBRAW_CANCELLED_BY_CALLBACK) throw std::runtime_error("RAW decoding cancelled");
    if (error != LIBRAW_SUCCESS) throw std::runtime_error("RAW decoding failed");
}
void checkDimensions(unsigned width, unsigned height) {
    if (!width || !height || width > MAX_DIMENSION || height > MAX_DIMENSION ||
        uint64_t(width) * height > MAX_PIXELS)
        throw std::runtime_error("RAW dimensions exceed supported limits");
}
}
namespace loupe {
RawImage::RawImage(const char* path, int maxSide, progress_callback callback, void* context)
    : raw_(std::make_unique<LibRaw>()) {
    if (!path || maxSide < 256 || maxSide > 2048)
        throw std::invalid_argument("Invalid RAW decode request");
    struct stat info{};
    if (stat(path, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0 ||
        uint64_t(info.st_size) > MAX_FILE_BYTES)
        throw std::runtime_error("RAW input exceeds supported limits or is unavailable");
    raw_->set_dataerror_handler(silenceError, nullptr);
    if (callback) raw_->set_progress_handler(callback, context);
    raw_->imgdata.rawparams.max_raw_memory_mb = 256;
    auto& p = raw_->imgdata.params;
    p.half_size = 1;
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.no_auto_bright = 1;
    p.gamm[0] = p.gamm[1] = 1.0;
    p.output_color = 1; // sRGB primaries, linear transfer through gamm.
    p.output_bps = 16;
    p.user_qual = 0;
    // Default user_flip=-1 preserves camera orientation; make_mem_image applies it.
    check(raw_->open_file(path));
    const auto& sizes = raw_->imgdata.sizes;
    checkDimensions(sizes.raw_width, sizes.raw_height);
    checkDimensions(sizes.width, sizes.height);
    check(raw_->unpack());
    if (raw_->error_count()) throw std::runtime_error("Corrupt RAW input");
    check(raw_->dcraw_process());
    if (raw_->error_count()) throw std::runtime_error("Corrupt RAW input");
    int sourceWidth, sourceHeight, colors, bits;
    raw_->get_mem_image_format(&sourceWidth, &sourceHeight, &colors, &bits);
    checkDimensions(sourceWidth, sourceHeight);
    if (colors != 3 || bits != 16) throw std::runtime_error("Unsupported RAW color format");
    int error = 0;
    image_ = raw_->dcraw_make_mem_image(&error);
    if (error || !image_) {
        if (image_) LibRaw::dcraw_clear_mem(image_);
        image_ = nullptr;
        throw std::runtime_error("RAW pixel conversion failed");
    }
    if (image_->type != LIBRAW_IMAGE_BITMAP || image_->colors != 3 || image_->bits != 16 ||
        image_->width != sourceWidth || image_->height != sourceHeight ||
        uint64_t(image_->data_size) != uint64_t(sourceWidth) * sourceHeight * 6) {
        LibRaw::dcraw_clear_mem(image_);
        image_ = nullptr;
        throw std::runtime_error("Unsupported RAW pixel layout");
    }
    const int longSide = std::max(sourceWidth, sourceHeight);
    width_ = longSide <= maxSide ? sourceWidth : std::max(1, int(int64_t(sourceWidth) * maxSide / longSide));
    height_ = longSide <= maxSide ? sourceHeight : std::max(1, int(int64_t(sourceHeight) * maxSide / longSide));
    // The oriented RGB16 copy owns all needed pixels; release the unpacked sensor buffers.
    raw_->recycle();
}
RawImage::~RawImage() { if (image_) LibRaw::dcraw_clear_mem(image_); }
void RawImage::write(float* output) const {
    if (!output) throw std::invalid_argument("Missing RAW pixel buffer");
    const auto* source = reinterpret_cast<const uint16_t*>(image_->data);
    const int sw = image_->width, sh = image_->height;
    for (int y = 0; y < height_; ++y) {
        const double sy = std::max(0.0, (y + 0.5) * sh / height_ - 0.5);
        const int y0 = int(sy), y1 = std::min(sh - 1, y0 + 1);
        const float fy = float(sy - y0);
        for (int x = 0; x < width_; ++x) {
            const double sx = std::max(0.0, (x + 0.5) * sw / width_ - 0.5);
            const int x0 = int(sx), x1 = std::min(sw - 1, x0 + 1);
            const float fx = float(sx - x0);
            for (int c = 0; c < 3; ++c) {
                const float top = source[(y0 * sw + x0) * 3 + c] * (1 - fx) + source[(y0 * sw + x1) * 3 + c] * fx;
                const float bottom = source[(y1 * sw + x0) * 3 + c] * (1 - fx) + source[(y1 * sw + x1) * 3 + c] * fx;
                *output++ = std::clamp((top * (1 - fy) + bottom * fy) / 65535.0f, 0.0f, 1.0f);
            }
            *output++ = 1.0f;
        }
    }
}
}
