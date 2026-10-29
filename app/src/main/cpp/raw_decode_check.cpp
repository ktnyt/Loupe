// Host-only smoke test: emits aggregate dimensions/range, never input names or pixels.
#include "raw_decode.h"
#include <cmath>
#include <iostream>
#include <vector>
int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--self-test") {
        for (int size : {0, 255, 2049}) {
            try { loupe::RawImage image("/dev/null", size); return 6; }
            catch (const std::invalid_argument&) {}
        }
        try { loupe::RawImage image("/dev/null", 2048); return 7; }
        catch (const std::runtime_error&) {}
        std::cout << "invalid maxSide and non-regular input rejected\n";
        return 0;
    }
    if (argc != 2 && argc != 3) return 2;
    try {
        bool cancel = argc == 3 && std::string(argv[2]) == "--cancel";
        auto cancelCallback = +[](void*, LibRaw_progress, int, int) -> int { return 1; };
        const bool mono = argc == 3 && std::string(argv[2]) == "--mono";
        loupe::RawImage image(argv[1], 2048, cancel ? cancelCallback : nullptr);
        if (cancel) return 8;
        std::vector<float> pixels(size_t(image.width()) * image.height() * 4);
        image.write(pixels.data());
        float minimum = 1, maximum = 0;
        double sum = 0;
        for (size_t i = 0; i < pixels.size(); ++i) {
            if (!std::isfinite(pixels[i]) || pixels[i] < 0 || pixels[i] > 1) return 3;
            if (i % 4 == 3) { if (pixels[i] != 1) return 4; continue; }
            if (mono && pixels[i] != pixels[i - i % 4]) return 9;
            minimum = std::min(minimum, pixels[i]); maximum = std::max(maximum, pixels[i]); sum += pixels[i];
        }
        if (maximum <= minimum || image.width() > 2048 || image.height() > 2048) return 5;
        std::cout << "decoded " << image.width() << 'x' << image.height() << " linear RGB range " << minimum << ".." << maximum
                  << " mean " << sum / (pixels.size() / 4 * 3)
                  << (mono ? " neutral RGB verified" : "") << '\n';
    } catch (const std::exception& e) {
        if (argc == 3 && std::string(argv[2]) == "--cancel" && std::string(e.what()) == "RAW decoding cancelled") {
            std::cout << "cancelled through LibRaw callback\n"; return 0;
        }
        std::cerr << e.what() << '\n'; return 1;
    }
}
