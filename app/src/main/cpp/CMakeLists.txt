cmake_minimum_required(VERSION 3.22.1)
project(nightjar-audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Oboe via prefab (provided by the com.google.oboe:oboe Gradle dependency)
find_package(oboe REQUIRED CONFIG)

# ── FluidSynth (synthesis engine) ─────────────────────────────────────────
# Built from source via FetchContent. All audio/MIDI drivers are disabled --
# we only use the synthesis core, calling fluid_synth_write_float() from our
# own render thread. The cpp11 OSAL eliminates the glib dependency.
include(FetchContent)

# Force FluidSynth options BEFORE FetchContent_MakeAvailable.
# We want a minimal static library: no audio drivers, no MIDI drivers,
# no glib, no optional dependencies.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(enable-aufile OFF CACHE BOOL "" FORCE)
set(enable-dbus OFF CACHE BOOL "" FORCE)
set(enable-ipv6 OFF CACHE BOOL "" FORCE)
set(enable-jack OFF CACHE BOOL "" FORCE)
set(enable-ladspa OFF CACHE BOOL "" FORCE)
set(enable-libinstpatch OFF CACHE BOOL "" FORCE)
set(enable-libsndfile OFF CACHE BOOL "" FORCE)
set(enable-midishare OFF CACHE BOOL "" FORCE)
set(enable-network OFF CACHE BOOL "" FORCE)
set(enable-opensles OFF CACHE BOOL "" FORCE)
set(enable-oboe OFF CACHE BOOL "" FORCE)
set(enable-oss OFF CACHE BOOL "" FORCE)
set(enable-alsa OFF CACHE BOOL "" FORCE)
set(enable-dsound OFF CACHE BOOL "" FORCE)
set(enable-wasapi OFF CACHE BOOL "" FORCE)
set(enable-waveout OFF CACHE BOOL "" FORCE)
set(enable-winmidi OFF CACHE BOOL "" FORCE)
set(enable-sdl3 OFF CACHE BOOL "" FORCE)
set(enable-pulseaudio OFF CACHE BOOL "" FORCE)
set(enable-pipewire OFF CACHE BOOL "" FORCE)
set(enable-readline OFF CACHE BOOL "" FORCE)
set(enable-openmp OFF CACHE BOOL "" FORCE)
set(enable-threads ON CACHE BOOL "" FORCE)
set(enable-floats ON CACHE BOOL "" FORCE)

# Use C++11 OSAL instead of glib (available since FluidSynth 2.5.0)
set(osal "cpp11" CACHE STRING "" FORCE)

FetchContent_Declare(
    fluidsynth
    GIT_REPOSITORY https://github.com/FluidSynth/fluidsynth.git
    GIT_TAG v2.5.3
    GIT_SHALLOW TRUE
)

FetchContent_GetProperties(fluidsynth)
if(NOT fluidsynth_POPULATED)
    FetchContent_Populate(fluidsynth)
    add_subdirectory(${fluidsynth_SOURCE_DIR} ${fluidsynth_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# ── Nightjar audio library ────────────────────────────────────────────────

add_library(nightjar-audio SHARED
    jni_bridge.cpp
    audio_engine.cpp
    oboe_recording_stream.cpp
    oboe_playback_stream.cpp
    wav_writer.cpp
    wav_track_source.cpp
    track_mixer.cpp
    synth_engine.cpp
    step_sequencer.cpp
    midi_sequencer.cpp
    metronome_sequencer.cpp
)

target_include_directories(nightjar-audio PRIVATE
    ${fluidsynth_SOURCE_DIR}/include
    ${fluidsynth_BINARY_DIR}/include
)

target_link_libraries(nightjar-audio
    oboe::oboe
    libfluidsynth
    android
    log
)
