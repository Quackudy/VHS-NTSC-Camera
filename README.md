# VHS Camera

A personal Kotlin camera app using the **real ntsc-rs effect engine** through
Rust/JNI. It records camera video with audio and a live effect preview, imports
presets through Android's file picker, and saves originals and processed H.264
MP4s to Gallery. Developer mode retains the image/video integration tools.
Processing stays on the phone. There is no network or broad storage permission.
The camera screen uses Camera and Microphone runtime permissions.

Version **0.5** adds live ntsc-rs preview to a Camera2 recorder and automatic Gallery exports: selected rear lens,
4:3 framing, crop-only W/T/pinch zoom, supported stabilization controls, and
H.264/AAC originals saved to Movies/VHS. The camera's **Live ntsc-rs preview**
checkbox starts enabled and uses the remembered imported preset (or the same
legacy defaults as export). Disable it to see the unprocessed camera. Recordings
can then be passed to the ntsc-rs exporter, which automatically saves a separate
processed video to the same Gallery folder. There is no fixed 15-second export
limit. The user brief is in [AGENTS.md](AGENTS.md).

Version **0.5.1** fixes **Process last clip** when Android restores the camera
as a standalone task (for example from Recents). It now opens the processing
screen itself if there is no activity-result recipient. The original remains
in Gallery while the effected copy is exported automatically.

## Camera default look (0.6.5)

Camera **Settings → Reset preset to default** restores the original ntsc-rs
legacy JSON `{"version":1}`. All engine settings, including head switching and
edge wave, are restored; 0.6.4's two disabled settings have been reverted.

For the built-in default, the app extends the source at its sides and bottom
before processing, then returns the original frame bounds. This moves the
filter-start stripe and bottom tracking/head-switching bands outside the visible
picture while retaining the original framing and the preset's texture/movement.
Both live preview and export use this path. Other imported JSON is processed
without this border treatment. Original recordings remain clean and unchanged.

Reset clears remembered imported JSON, filename and compatibility warnings;
other camera preferences are preserved. Developer mode has the same button.
Native tests check the returned picture against upstream processing of an
explicitly extended frame. Native tests/Clippy, Android build/lint, signing/16KiB
alignment and the on-device synthetic suite passed. Code 14 is installed; visual
verification is waiting for the phone to be unlocked.

## Launcher icon (0.6.2)

The launcher uses the user's workspace `app_image.jpg`: density-specific PNGs
and an adaptive icon with a padded foreground and a matching cyan background.
The original artwork remains unchanged. Icon resources and manifest wiring
passed build/lint, APK signing/alignment and aapt resource checks; code 11 is
installed on the A26. The effect/recording implementation is the tested 0.6.1
code. A dedicated monochrome/themed-icon layer is not provided.

## Preset import fixes (0.6.1)

Import now displays the source filename. Invalid files (including PDFs), bad UTF-8,
unknown fields and unsupported versions show a rejection dialog and retain the
previous selection. The picker still accepts varied file types so providers can
expose valid presets with unusual MIME types; file contents decide validity.
Known retired scanline-phase fields load with an explicit compatibility notice;
they are not applied by this pinned core. Supported numeric values follow the
same clamping as upstream, with adjustments reported. Original JSON, filename and
warnings are remembered; JSON keys are not rewritten or discarded.

A camera-startup race could retain the old LivePreview snapshot after importing.
Configured sessions now compare their preset snapshot against the selection and
rebuild if it changed; stale frames are not displayed as the newly selected effect.
The `cameraPreset=true` instrumentation mode tests this ordering using test-owned
fixtures and preview only, never camera recording or microphone capture. Obtain
explicit readiness first. It restores the saved preset/name/warnings afterward.

[Diagnostic presets](docs/preset-checks/README.md) differ only in snow intensity.
Compare the live effect preview or a processed copy, since originals remain clean.
The user's two already obtained local preset copies were checked against the
pinned upstream parser: supported settings match exactly; retired controls are
reported. The temporary local debug copies were removed after comparison and were never
committed. Subsequent phone-file reads require explicit user permission.

Version 0.6.1 passed eight native tests/clippy, Android build/lint, signing/alignment,
the synthetic A26 suite, and an authorized preview-only import test. The latter
forces the session-startup ordering, checks legacy compatibility notices and
PDF rejection, and restores the user's preset/filename/warnings. No video or
microphone capture occurs in that test.

## Camera controls (0.6)

Version 0.6 opens directly into the camera. The large red button starts/stops
recording; **Pause** changes to **Resume**, continuing in the same MP4. The timer
freezes while paused, and the paused interval is omitted from saved audio/video.
The live viewfinder remains active while paused. **Stop** also saves a paused
recording. Leaving the app finalizes the video; pause does not support background
recording or resuming a video after closing the app.

To set the starting zoom, select a lens in **Settings**, return to the viewfinder,
and adjust W/T or pinch. Open **Settings** again and tap **Set … as default**.
The value is remembered separately for each exposed lens and clamped to its
supported crop range when loaded. Starting the camera or switching to that lens
applies the saved default. This is crop zoom; the app never changes lenses to
implement zoom. Normal rotation preserves the current lens/zoom and last clip.

**Settings** contains lens selection, preset import, live preview, stabilization,
starting zoom and zoom speed. **Developer mode** opens the former image benchmark
and video-processing screen. Normal camera exports show a smaller progress/save
screen; returning from it reopens the camera. Originals still save immediately
to Gallery Movies/VHS; use **Process last clip** to save a separate VHS copy.
The previous recording remains accessible after restarting the app unless it
has been removed from Gallery.

Build/install the same way as below. The launcher now opens `CameraActivity`;
`MainActivity` is the private developer/export screen. The default synthetic
instrumentation suite adds generated EGL-to-MediaRecorder pause/resume and
stop-while-paused checks without opening the camera/microphone. Hardware
`cameraRecord=true` now records ~4 seconds over ~6 seconds including a 2-second
pause; invoke only after explicit user readiness.

Version 0.6 is installed and passed the synthetic A26 suite, including generated
pause/resume and stop-while-paused recorder checks, plus the approved real
camera/audio test. Saved 1.5× default zoom reloaded correctly; hold-T kept rear 2
and its 1.64mm focal length. The ~6-second recording with a 2-second pause produced
121 video frames (4.2-second video endpoint) plus AAC; the MP4 container is
4.416 seconds including audio timing/tail. The original is Gallery item 7424;
normal camera-button export published a 360×480 effected copy as item 7425.
All 207 AAC packet payloads and all audio/video timestamps were preserved
(within 100µs), and the original SHA256 was unchanged. No 2-second timestamp gap
remains; the largest source video packet gap was about 90ms, audio about 21ms.
These checks establish file/timestamp behavior, not speech/clap perceptual sync.

Actual-device ready-screen captures at normal and 1.3× Android font scale passed
portrait/landscape layout review; the landscape Settings dialog was also checked.
Active pause/record states passed functional checks but were not visually captured.
The preview sample reported 28.7fps, 13.7ms/frame, 2 skipped frames, PSS 102–109MiB
and thermal status 0 during only five seconds. Long-session heat and stability
remain unverified. Original orientation/font settings were restored and the
temporary test package was removed after checks. Build/lint, six native tests,
signing/alignment and host zoom/timer checks also passed.

## Live preview (0.5)

Open camera from the main screen. The live preview uses the smallest exposed
4:3 YUV stream between 320 and 640 pixels wide, normally 320×240 (upright
240×320 in portrait). The counter reports displayed-throughput fps, mean
conversion/effect/bitmap processing time, and skipped nominal 30 fps frames.
Processing and display each have at most one frame in flight; stale camera
buffers are dropped with `acquireLatestImage()`. Leaving the camera closes the
reader on its conversion thread, after JNI has finished using its buffers.

The recorder still saves **unprocessed originals with audio**. Tap **Process
last clip** to produce the effected video automatically in Gallery Movies/VHS.
Live processed recording is not implemented. Live preview and export use the
same validated preset JSON. Preview uses upstream's scale factor to compensate
for reduced resolution unless the preset already enables video-size scaling.
The preview is an approximation: resampling, interlace/scanline detail, random
frame phase and camera-vs-codec color conversion can differ from export. No
supported preset settings are silently omitted. Retired scanline-phase controls
are explicitly reported as unapplied. Camera color metadata is used where available;
unspecified components fall back to BT.601/limited with an on-screen notice.

Live processing also runs while recording originals. If a lens rejects the YUV
plus recorder stream combination, the app reports it; disable live preview to
use the existing recorder. Live preview cannot be toggled during a recording.
Check orientation, framing, appearance, responsiveness, and heat on the actual
phone. Short automated checks do not establish long-session reliability.

## Feasibility and decisions

- Upstream inspected at commit
  [`8cfc51014092a6dc1da5eb0217bb9ae6516b124e`](https://github.com/ntsc-rs/ntsc-rs/tree/8cfc51014092a6dc1da5eb0217bb9ae6516b124e).
  Only `crates/ntscrs` is linked. Its CPU effect core uses `fearless_simd`,
  `num_enum`, `hifijson`, and optional Rayon/CPU-count support. Default `std` and
  `rayon` features are enabled. Desktop GStreamer, egui, After Effects and OpenFX
  code are not built. Cargo may fetch upstream's OpenFX git submodule as part of
  fetching the repository; that does not make it a linked dependency.
- Core license: **MIT OR ISC OR Apache-2.0**. Direct bridge dependencies `jni`
  and `serde_json` use MIT/Apache licensing. License texts and dependency metadata
  are bundled under `app/src/main/assets/licenses/`. The fetched `hifijson`
  package declares MIT but omits its license text; the recorded repository commit
  was unavailable when checked. Its metadata is retained in the license index;
  resolve that text before distributing the app. This inspection concerns
  the linked core; it is not a publishing clearance for the entire desktop app.
- Kotlin uses platform Views and file-picker APIs to keep the proof small. JNI
  is the boundary that lets Kotlin call the Rust shared library. The **NDK**
  supplies Android's native linker; ordinary desktop Rust output cannot run on
  Android. Gradle invokes Cargo before packaging the `.so` into the **APK**
  (Android's installable application package).
- Initial build: Android 10/API 29+, ARM64 (`arm64-v8a`), compile/target API 35.
  These are deliberate proof limits, not a commitment to broad compatibility.
  [AGP 8.9.2](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
  uses Gradle 8.11.1 and requires JDK 17+. NDK 28.2.13676358 is pinned; the native
  library is linked with 16 KiB page alignment. No Play release is configured.
- Presets: upstream's flat version-1 JSON format. Recognized settings all go to
  the unmodified core. Unknown keys, unsupported versions, invalid enums/types
  fail visibly. The two retired keys `video_scanline_phase_shift` and
  `video_scanline_phase_shift_offset` are accepted with an explicit compatibility
  notice because the pinned core no longer applies them. Numeric settings follow
  upstream clamping, with each adjusted value reported. Missing keys retain **upstream legacy defaults**, not
  guesses from this app. ntscQT presets are explicitly outside this first proof.
- The test uses RGB8, scale `[1, 1]`, original `use_field` settings, and upstream
  **Bob deinterlacing** for a progressive display. It does not create interlaced
  video files. Inputs are decoded into sRGB, downscaled within 640×480, preserving
  their aspect ratio; the bundled pattern is 4:3. Imported images are not cropped.
  Saved output has the exact dimensions displayed in the test.
- JNI currently copies pixel arrays and allocates per frame. The timings include
  those costs and preset parsing. This proves integration, not maximum engine
  speed. Reusing buffers/context and measuring thread counts comes after actual
  device measurements. The core already retains its context/thread pool.

## Build on CachyOS/Linux

1. Install JDK **development tools**, Rust and the usual command-line utilities:

   ```bash
   sudo pacman -S --needed jdk17-openjdk rustup git unzip
   rustup default stable
   rustup target add aarch64-linux-android
   ```

   Rust 1.99.1 was used for this proof. Use current stable Rust; the upstream
   crate uses the 2024 edition. `java` alone is insufficient: `javac` must exist.

2. Install [Android Studio](https://developer.android.com/studio/install#linux).
   Open this folder as a project. Under **SDK Manager**, install:

   - Android SDK Platform 35
   - SDK Build-Tools 35.0.0 and Platform-Tools (`adb`)
   - NDK (Side by side) **28.2.13676358**; enable **Show Package Details** to find it

   The SDK provides Android APIs/build tools. The NDK provides native C/C++
   toolchains used to link Rust. You do not need CMake for this project. Review
   and accept Google's SDK licenses in the SDK Manager.

3. In a terminal, set paths to your actual installations:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
   export ANDROID_HOME="$HOME/Android/Sdk"
   export PATH="$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
   ./gradlew assembleDebug
   ```

   `./gradlew` downloads the pinned Gradle version; Gradle downloads its plugins,
   and Cargo downloads the pinned upstream revision/dependencies. These are
   development downloads; the installed app needs no network. Android Studio
   must inherit these environment variables too if building from the IDE.

## Install and test on the Samsung A26

1. On the phone enable Developer options (Settings → About phone → Software
   information → tap Build number seven times), then USB debugging. Connect USB,
   unlock the phone and approve this computer's debugging fingerprint.
2. Run:

   ```bash
   adb devices -l
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n dev.vhs.proof/.CameraActivity
   ```

   If Linux USB permissions block access, install/configure your distribution's
   Android udev rules; reconnect USB. Do not run adb as root. Android Studio's
   Run button can also build and install the app.
3. Tap **Process and measure 10 frames**. Check the pattern changes, toggle
   **Show processed image**, then save a PNG. Default settings come from
   upstream's legacy version-1 defaults.
4. Put a real ntsc-rs preset and a photo on the phone, then use **Import ntsc-rs
   preset** and **Choose test image**. Unknown settings must produce an error
   while retaining the previous valid preset. For a quick rejection check, put
   `{"version":1,"unknown_test_setting":true}` in a `.json` file and import it.
5. For a visual reference, use the same source pixels, preset, frame number
   **9**, RGB8/sRGB, Bob deinterlacing, and scale `[1,1]` in the pinned upstream
   engine. Import the source at 640×480 to avoid downscaling differences. Desktop
   UI defaults, video color conversions and different engine revisions can
   otherwise change the comparison. Native tests already compare the bridge
   output to direct upstream calls, but phone/desktop visual comparison is still
   required.
6. Record mean/min/max milliseconds, image size, preset, device model, Android
   version and whether the phone was cool. Repeat after a few runs. Ten frames
   do **not** measure sustained thermal behavior or codec throughput. Send these
   values before deciding what live processing is feasible.

Imported preset JSON is validated and copied into private SharedPreferences,
then restored on future launches and app updates. Import once after upgrading
from older versions that did not remember it. Re-import to apply changes to the
external preset file. A failed attempt to remember the preset is reported.

Exports currently belong to the open screen. Display orientation is locked
during export, and the screen stays awake. Keep the app open; destroying the
activity requests cancellation. Background/process-death recovery is deferred.
Image-test state and the last-saved-video button reset on activity recreation;
published videos remain in Gallery. The PNG image test still uses the system
save dialog; a failed PNG write may leave a partial destination.

## Import and export a video

1. Update with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   Version 0.4 was also installed directly on the connected A26 during development.
2. Import your ntsc-rs preset once; the selected settings are remembered. Scroll
   down to **Video export**.
3. Tap **Choose and process video**. Start with a 3–5 second SDR phone clip
   containing speech or a visible hand clap. Keep the app open while it works.
4. Watch video percentage/frame count, followed by audio copy, finalization and
   Gallery saving. Unknown-duration inputs show an indeterminate progress bar.
   **Cancel export** cooperatively stops at a frame/copy boundary and cleans up
   unfinished cache/Gallery output, keeping the source and already-published
   videos. Cancellation arriving after publication leaves the completed video.
   The final processing time includes
   initialization, YUV/RGB conversion, the real Rust effect, encoding and audio
   copying; it excludes copying the completed MP4 to Gallery.
   Version 0.2.1 additionally reports mean YUV→RGB handling, ntsc-rs/JNI, RGB→YUV
   handling, and the remaining codecs/setup/audio time. The JNI line is closest
   to the earlier image benchmark; the video means also include first-frame
   work, while the image test explicitly warms up first. Conversion timings
   include acquiring/closing codec images and reading their planes, not just
   the color arithmetic. The remainder is elapsed time outside those regions,
   not an isolated measurement of codec CPU time.
5. Export automatically saves to **Gallery → Movies/VHS** as
   `VHS-processed-<timestamp>.mp4`; there is no video save dialog or filename entry.
   Tap **Open saved video** to inspect it. Compare against the
   source for orientation, aspect ratio, effect appearance and speech/clap sync.
   Try both a landscape and portrait source. Originals stay untouched.

Current limits are explicit: one video track, at most one AAC audio track, up to
60 fps, input up to 4096 pixels per side, square pixels, and 8-bit
SDR BT.601/BT.709. HDR, other audio codecs and other color matrices are rejected.
A silent source produces a silent export. Audio is copied, not decoded or
re-encoded. Only video and optional audio tracks are retained; other metadata
tracks are outside this proof.

The pipeline is Android **MediaExtractor → MediaCodec YUV images → RGB →
ntsc-rs → YUV → MediaCodec H.264 → MediaMuxer MP4**. Output is limited to 640×480,
with dimensions rounded down to encoder alignment, preserving the aspect ratio
within that rounding. This is not the future camera's 4:3 crop. Scaling currently
uses nearest-neighbor sampling. Rotation is applied to pixels before the effect;
the result is upright with horizontal VHS scanlines and no rotation tag.
RGB is encoded as BT.601 limited-range YUV420 at 3 Mbit/s.

Version **0.2.2** performs the YUV/RGB loops in Rust over direct codec image
buffers. Kotlin passes views respecting each plane's position and limit. The
native code validates dimensions, strides, accessible buffer lengths and output
writability before accessing pixels. Interleaved U/V planes may share storage.
Color formulas, crop/rotation and nearest-neighbor sampling are unchanged; the
effect engine and preset settings are unchanged. The old Kotlin loops remain
only in the test APK as the output reference. No additional dependency was added.

Missing decoder color fields fall back to declared source fields. Where both
are absent, the app reports its BT.601/limited-range assumption. The A26 decoder
emits [AOSP extended color standard 71](https://android.googlesource.com/platform/frameworks/av/+/f56a02a5e7d1bfaeabf8b918e56bf49ea509bd42/media/libstagefright/foundation/ColorUtils.cpp)
(BT.709 matrix with unspecified primaries); that observed case is recognized.
Other unsupported standards fail visibly.

Processing streams frames into a temporary MP4 in app-private cache. The completed
file is copied into a pending MediaStore item and published only after copying
finishes. Cancellation/failure deletes that new pending item; the temporary cache
file is cleaned up by the exporter. Playback uses an installed video player.
No storage permission or new library was added. A process killed without cleanup
can leave a cache/pending item; crash recovery is not implemented yet.

## Shoot with the camera (version 0.5)

The update was installed on the A26 during development. On a fresh installation,
tap **Open camera · live ntsc-rs preview** and allow Camera and Microphone access.
The approved hardware test in this session already granted those permissions.
Image/video import still works without opening the camera.

1. On the test screen, import the preset you want to use, then open the camera.
2. The widest exposed rear lens, **Rear 2**, is selected by default. The lens
   dropdown offers **Rear 0** (main) as well. Each selection opens that camera ID
   directly; zoom never changes it. FoV labels are estimates from sensor metadata.
3. Hold **T** to crop in, **W** to crop out. The zoom speed slider changes the
   continuous rate; speed ramps up/down over approximately 180 ms. Pinch is also
   supported. Accessibility clicks on W/T make a small zoom step. Zoom is relative
   to the selected lens, so `1× crop` on the ultrawide is its widest framed view.
4. EIS and OIS start OFF. The ultrawide reports OFF-only for both. Main-camera OIS
   supports ON/OFF; EIS reports OFF-only on both rear cameras. Unsupported controls
   are disabled and labeled. Reported capture states are shown below the preview;
   tap that area for the full exposed-camera report.
5. Tap **Record**, shoot a 3–5 second test, then **Stop**. Portrait and landscape
   layouts are supported. Recording locks the display orientation at its start;
   it stops/finalizes if the camera screen is paused or left. The original saves
   to **Movies/VHS** and can be opened from Gallery. Stopping very quickly may
   fail to finalize a valid MP4; the app reports that failure.
6. Tap **Process last clip**. This closes the camera and processes the original
   using the test screen's remembered preset. The processed video saves to Gallery
   automatically. This does not replace or delete the Gallery original.

Camera2 is used here because the A26 exposes separate rear camera IDs and we need
explicit crop and stabilization requests. MediaRecorder coordinates H.264/AAC
capture and timestamps. MediaStore publishes originals to Gallery without broad
storage access. Unprocessed preview and recorder use 640×480; live preview uses
a smaller YUV stream with the same centered 4:3 crop request. The preview fits inside its black background with no extra framing
crop. TextureView handles sensor orientation; the app corrects display rotation,
while MediaRecorder writes the corresponding orientation tag. Portrait originals
therefore still store 640×480 pixels with a rotation tag; the processed export is
upright (360×480 within the current portrait export limit).

Measured public capabilities on this A26:

| Rear camera | Focal length | Max crop zoom | EIS | OIS | Common 4:3 preview/record sizes |
| --- | --- | --- | --- | --- | --- |
| 2, ultrawide/default | 1.64 mm | 8× | OFF only | OFF only | 640×480, 960×720 |
| 0, main | 4.0 mm | 8× | OFF only | ON/OFF | 640×480, 960×720 |

See [the device report](docs/a26-camera-capabilities.txt) for the characteristic
values and hardware-test evidence. In the approved recording test, camera ID 2
and its 1.64 mm focal length remained unchanged while hold-T changed the reported
crop to approximately 1.41×; reported EIS and OIS remained OFF.

Current camera-proof limits: rear directly exposed IDs only; logical/physical
stream routing on other devices is explicitly rejected. The front camera is not
offered yet. Recording is foreground-only, and camera-screen selections/last-clip
state reset on activity recreation. Originals remain in Gallery. Live preview is available; live processed recording is not implemented. Recording and processing have
no fixed short-clip duration cap. The user subsequently
confirmed preview/export framing, landscape orientation, handheld zoom feel,
audible sound sync and sustained heat were all fine. Exact test durations and
conditions were not supplied. Main-camera OIS ON response was not explicitly
confirmed; live effect-preview performance/heat will require separate testing.

## Checks

Version 0.5.1 passed build/lint, six native tests and the synthetic phone suite,
including standalone startup and normal activity-result exports. Retrying the
user's existing 5.14-second camera clip produced all 147 frames and 241 identical
AAC packets, with video/audio timestamps preserved within 100µs and unchanged
source SHA256. Both videos remain in Gallery. No new camera/microphone test was
needed for this handoff fix.


Version 0.4 passed build/lint (zero errors), the existing video/native-conversion
regressions, and new on-device workflow tests for remembered preset settings,
automatic Gallery saving, UI cancellation, mid-processing cancellation,
pending-output cleanup, and unchanged source bytes. The tests restore the user's
previous saved preset and remove only their own synthetic Gallery output.

A generated **60-second, 640×480, 30 fps SDR/AAC clip** exported on the A26 in
**69,420 ms** with upstream legacy defaults. All **1,800 video frames** and video
timestamps (within 100 µs of muxer rounding), plus every AAC payload/timestamp,
were preserved. Added A/V end-offset drift was **0 µs**. App PSS sampled every
300 frames ranged **79–103 MiB** (first warm sample 88 MiB); maximum reported
Android thermal status was **0 (NONE)**. This is sampled app memory and an OS
thermal signal, not a temperature measurement or proof for arbitrary clip lengths.
This backend instrumentation run used default settings, unlike the user's earlier
18 ms/frame measurement. Gallery output matched the completed MP4 byte-for-byte;
cancelling a partial Gallery copy removed its pending item. No camera or microphone
was activated for these tests.

To repeat that long test, generate the fixture on Linux and upload it to the
debug app's private cache after installing the app and test APK:

```bash
ffmpeg -f lavfi -i testsrc2=size=640x480:rate=30:duration=60 \
  -f lavfi -i sine=frequency=880:sample_rate=48000:duration=60 \
  -c:v libx264 -preset ultrafast -crf 30 -profile:v baseline -pix_fmt yuv420p \
  -bf 0 -g 30 -color_primaries bt709 -colorspace bt709 -color_trc bt709 \
  -color_range tv -c:a aac -b:a 64k -movflags +faststart -y /tmp/long-fixture.mp4
adb exec-in run-as dev.vhs.proof sh -c 'cat > cache/long-fixture.mp4' < /tmp/long-fixture.mp4
adb shell am instrument -w -e longExport true \
  dev.vhs.proof.test/dev.vhs.proof.ProofInstrumentation
```

The test deletes its uploaded source and synthetic Gallery output afterward.
FFmpeg is only a host-side fixture generator; it is not used by the Android app.

Verified here on 2026-11-01: ARM64 Android release-library compilation; four
native tests; Clippy with warnings denied; debug APK assembly and Android lint
(zero errors, warnings for proof-only localization/icon/ABI/backup omissions).
ELF load segments have 16 KiB alignment. No adb device was attached: installation,
screen layout, image-picker behavior, visual matching and phone timings remain
unverified. No live/video speed is claimed.
A Linux JVM smoke test also verified the JNI method bindings, processing and
Java exception propagation; it is not an Android runtime test.

Subsequently, version 0.2 was built, installed and tested on the actual A26
SM-A266B, running Android 16/API 36 (read via adb). Four synthetic one-second
fixtures passed: normal AAC, rotated AAC, AAC with video starting 120 ms later,
and silent video. Each retained all 30 frames. Video timestamps matched within
100 microseconds of muxer timebase rounding; AAC payload hashes and timestamps
matched exactly. The rotated output was 240×320 with no rotation tag. Tests also
check YUV strides, nonzero plane offsets, color conversion and all four rotation
directions. Processing including setup took 2.018–2.281 seconds per one-second
320×240 fixture (240×320 for the rotated output), using upstream legacy defaults.
This is not the user's preset benchmark or a live-performance claim. Perceived
color/effect quality, real-footage A/V sync and sustained thermal performance
still need manual checks.

Version 0.2.2 additionally passed five Rust tests, Clippy with warnings denied,
APK assembly and Android lint (zero errors), and the A26 instrumentation suite.
Native conversion matched the pre-optimization Kotlin output exactly in 48
crop/scaling/rotation/color combinations and in encoded YUV plane bytes/padding.
Tests reject heap buffers, read-only output, insufficient plane capacity,
negative crops and invalid strides. The four exported synthetic clips still
retained their frames, audio and timestamps. In this run, total export times
were 779/707/644/727 ms for the normal/rotated/offset/silent one-second fixtures.
YUV→RGB handling averaged 2.45–3.01 ms/frame and RGB→YUV 0.55–0.66 ms/frame at
320×240 (240×320 for the rotated fixture), with upstream legacy defaults. These
are short synthetic measurements, not sustained performance with the user's preset.

To repeat the on-device tests (the runner accesses only synthetic test assets
and app-private cache):

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.vhs.proof.test/dev.vhs.proof.ProofInstrumentation
adb uninstall dev.vhs.proof.test
```

The instrumentation runner prints `PASS` or `FAIL` with details. It deliberately
uses platform Instrumentation and Kotlin `check`, so no test dependency is
required. Fixture provenance/reproduction is in
[the test assets README](app/src/androidTest/assets/README.md).

The toolchain used for this session lives temporarily in `/tmp/vhs-tools`
(Android SDK and JDK), `/tmp/vhs-cargo` and `/tmp/vhs-rustup`; it is not a
persistent development installation. The steps above set up your normal tools.

```bash
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
./gradlew assembleDebug lintDebug
```

Tests cover upstream-generated preset round trips, legacy defaults, invalid
presets, frame-size validation, channel conversion and output equality against
the direct upstream engine at frame indices 0, 1 and 29. Android lint checks the
Kotlin/platform API usage. Neither proves device performance.

## Next increments

### First device benchmark (user reported)

Samsung Galaxy A26, One UI 8.5; app installed and opened successfully. An imported
ntsc-rs preset was active (preset name/file not yet recorded).

| Measurement | Result |
| --- | --- |
| Image dimensions | 640 × 427 |
| Measured frames | 10 after warm-up |
| Mean | 13.7 ms |
| Minimum / maximum | 10.5 / 17.9 ms |
| Image-processing equivalent | 73.1 frames/s |

These timings include the current JNI copies, preset parsing and allocations.
They support proceeding to short-video testing, but do not establish sustained
video/camera throughput. Codec costs, heat, dropped frames, audio sync and visual
matching against desktop output remain unverified.

The user subsequently reported approximately **88 ms/frame** for the complete
video pipeline with a preset. That is not directly comparable to the image
test's 13.7 ms JNI-only result. The exact clip dimensions/duration and stage
breakdown for that report are not yet recorded. Version 0.2.1 was installed on
the A26 to expose the separate costs. Its four synthetic-clip tests passed,
including timing accounting; measured conversion and effect times varied
substantially across the sequence, so no sustained speedup is claimed.

The subsequent user-reported stage breakdown was **95.5 ms/frame total**:
63.1 ms YUV→RGB handling, 7.3 ms ntsc-rs/JNI, 20.4 ms RGB→YUV handling, and
4.8 ms other. The two conversion regions account for about 87% of that time.
This motivated version 0.2.2's native converters. Reprocess the same real clip
with the same preset to measure the improvement directly; the synthetic results
above do not substitute for that comparison.

After installing version 0.2.2, the user reported:

| Stage | Mean ms/frame |
| --- | ---: |
| Total | 18 |
| YUV → RGB handling | 4.5 |
| ntsc-rs/JNI | 7.7 |
| RGB → YUV handling | 1.0 |
| Other | 4.5 |

That is about 5.3× faster overall than the earlier 95.5 ms result. Values are
rounded; the components need not sum exactly to the rounded total. The exact
clip dimensions/duration and preset filename remain unrecorded. An 18 ms/frame
offline average is below the 33.3 ms/frame budget for 30 fps; it does not establish
sustained live camera performance, thermal behavior or dropped-frame rates.

### Camera hardware validation

Version 0.3 passed build/lint (zero errors), crop/rotation/zoom-motion assertions,
and the existing synthetic video suite. With the user's explicit readiness for
a roughly three-second camera/audio test, it recorded and published a **3093 ms
H.264/AAC original (2873 ms video track), 640×480, rotation tag 90°, 84 video frames** on the A26. It
processed all 84 frames to 360×480 in **2307 ms**, using upstream legacy defaults;
AAC payloads and the A/V start offset were preserved. The original remains in
Movies/VHS. This verifies file structure and timing preservation, not perceived
sound sync or sustained live performance.
This test exported while the camera preview was still active. The normal
**Process last clip** flow closes the camera before exporting.

The default instrumentation suite is synthetic and does not open camera/mic.
`-e cameraProbe true` prints public characteristics without capture. The separate
hardware mode below **activates camera and microphone and keeps one original
test recording**; run it only when the phone owner is ready and the camera points
at an appropriate scene. Allow camera/mic permissions through the app first.

```bash
adb shell am instrument -w -e cameraRecord true \
  dev.vhs.proof.test/dev.vhs.proof.ProofInstrumentation
```

### Implementation order

1. Complete manual validation with the user's preset on real short
   clips: inspect effect/color, orientation, duration and perceptual A/V sync.
   Compare output against the pinned desktop core using equivalent input pixels,
   settings and frame indices. Keep the source and export to a new file.
2. Manually check version 0.3's preview versus recorded framing in both phone
   orientations, W/T start/stop feel, pinching, main OIS ON/OFF, sound audibility
   and speech/clap sync. Iterate on shooting controls from those observations.
3. Validate longer real recordings with the user's preset, including low-storage
   failures, interruption and recovery. Foreground progress/cancellation and
   automatic Gallery saving are implemented; persistent background jobs and
   process-death recovery remain deferred. Keep originals and measure memory,
   frame gaps, heat and audio drift under those conditions.
4. Benchmark reduced-resolution live preview, then consider live processed
   recording only if sustained speed, dropped frames, heat, memory and A/V drift
   are acceptable on the phone.

[Samsung's A26 specification table](https://news.samsung.com/uk/samsung-marks-a-step-forward-with-ai-for-everyone-by-introducing-new-galaxy-a56-5g-galaxy-a36-5g-and-galaxy-a26-5g)
lists an 8 MP ultrawide and a 50 MP main camera with OIS. The device report and
recording test above now confirm access to rear ID 2, unchanged focal length
during crop zoom, and capture results reporting EIS/OIS OFF. Handheld movement
still needs user evaluation. Android documents device-dependent
[physical-camera access](https://developer.android.com/media/camera/camera2/multi-camera)
and separate [video/OIS controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest).
One UI 8.5 is user reported; Android 16/API 36 and the exposed camera catalog were
verified via adb/instrumentation. Further behavioral checks are listed above.
