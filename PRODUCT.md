# Product
<!-- impeccable:product-schema 1 -->

## Platform
android

## Stack
Delegated by the user: native Kotlin and the actual Rust ntsc-rs core through JNI.
Camera framework to be selected after hardware capability inspection.

## Users and purpose
A programmer new to Android wants authentic camcorder/VHS footage on their own
phone, with local preset import, deliberate zoom and camera movement.

## Constraints
See AGENTS.md for the user brief and milestone order. No network services or
monetization. Footage and shooting experience take priority over fancy UI.
Current surfaces: image/video processing and a Camera2 recording
proof with 4:3 framing, selectable exposed rear lenses, crop-only W/T/pinch zoom,
separate stabilization controls, and original H.264/AAC saving. An optional
low-resolution live ntsc-rs preview uses the remembered preset; originals remain
unprocessed and can be sent to the ntsc-rs processor. Processed videos
automatically save to Gallery Movies/VHS with generated filenames. Preset JSON
is remembered between launches. Exports show progress and offer cancellation;
there is no fixed 15-second cap. Background/process-death recovery is deferred.
Device: Samsung Galaxy A26 SM-A266B, One UI 8.5; Android 16/API 36 verified by adb.
The user wants lens choice, defaulting to the widest exposed rear lens (0.5x
ultrawide), and crop zoom that never changes the selected physical camera.
