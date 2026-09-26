#include "track_mixer.h"
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <string>
#include <vector>

// Standalone Android executable: exercises the production mixer with known PCM.
static bool writeWav(const std::string& path, int frames, int base) {
    FILE* file = std::fopen(path.c_str(), "wb");
    if (!file) return false;
    const uint32_t bytes = frames * 2;
    const uint32_t size = 36 + bytes, formatSize = 16, rate = 44100, byteRate = 88200;
    const uint16_t pcm = 1, channels = 1, alignment = 2, bits = 16;
    std::fwrite("RIFF", 1, 4, file); std::fwrite(&size, 4, 1, file);
    std::fwrite("WAVEfmt ", 1, 8, file); std::fwrite(&formatSize, 4, 1, file);
    std::fwrite(&pcm, 2, 1, file); std::fwrite(&channels, 2, 1, file);
    std::fwrite(&rate, 4, 1, file); std::fwrite(&byteRate, 4, 1, file);
    std::fwrite(&alignment, 2, 1, file); std::fwrite(&bits, 2, 1, file);
    std::fwrite("data", 1, 4, file); std::fwrite(&bytes, 4, 1, file);
    for (int i = 0; i < frames; ++i) {
        const int16_t sample = static_cast<int16_t>(base + i);
        std::fwrite(&sample, 2, 1, file);
    }
    return std::fclose(file) == 0;
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const std::string guitar = std::string(argv[1]) + "/capture-loop-guitar.wav";
    const std::string melody = std::string(argv[1]) + "/capture-loop-melody.wav";
    if (!writeWav(guitar, 441, 100) || !writeWav(melody, 1014, -2000)) return 3;
    nightjar::TrackMixer mixer;
    if (!mixer.addTrack(1, guitar, 10, 0, 0, 0, 1, false, true, 137, 1014)
        || !mixer.addTrack(2, melody, 23, 0, 0, 0, 1, false, true, 0, 1014)) return 4;
    std::vector<float> output(511 * 2);
    int checked = 0;
    for (int mode = 0; mode < 4; ++mode) {
        if (mode > 0) {
            mixer.removeAllTracks();
            if (!mixer.addTrack(1, guitar, 10, 0, 0, 0, 1, false, mode != 2, 137,
                mode == 3 ? 220 : 0)) return 5;
        }
        for (int64_t position : {0LL, 440LL, 999LL, 1000LL, 1014LL, 2024LL, 8248LL}) {
            mixer.renderFrames(output.data(), 511, position);
            for (int i = 0; i < 511; ++i) {
                const int64_t frame = position + i;
                float expected;
                if (mode == 0) {
                    const auto local = frame % 1014;
                    expected = static_cast<float>(100 + (local + 137) % 441 - 2000 + local) / 32768;
                } else if (mode == 1) {
                    expected = static_cast<float>(100 + (frame + 137) % 441) / 32768;
                } else if (mode == 2) {
                    expected = frame < 441 ? static_cast<float>(100 + frame) / 32768 : 0;
                } else {
                    expected = static_cast<float>(100 + (frame % 220 + 137) % 441) / 32768;
                }
                for (int channel = 0; channel < 2; ++channel) {
                    if (std::fabs(output[i * 2 + channel] - expected) > 0.000001f) {
                        std::fprintf(stderr, "Mismatch mode=%d frame=%lld channel=%d\n",
                            mode, static_cast<long long>(frame), channel);
                        return 6;
                    }
                    ++checked;
                }
            }
        }
    }
    std::remove(guitar.c_str()); std::remove(melody.c_str());
    std::printf("PASS: %d samples; related cycles, independent loops, ordinary playback\n", checked);
    return 0;
}
