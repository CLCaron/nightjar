cmake_minimum_required(VERSION 3.22.1)
project(nightjar-audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Oboe via prefab (provided by the com.google.oboe:oboe Gradle dependency)
find_package(oboe REQUIRED CONFIG)

add_library(nightjar-audio SHARED
    jni_bridge.cpp
    audio_engine.cpp
    oboe_recording_stream.cpp
    oboe_playback_stream.cpp
    wav_writer.cpp
    wav_track_source.cpp
    track_mixer.cpp
)

target_link_libraries(nightjar-audio
    oboe::oboe
    android
    log
)
