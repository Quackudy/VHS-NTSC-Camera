# VHS camera project

The developer uses CachyOS/Linux, can program, and is new to Android. Explain
Android setup, builds, installation and platform decisions clearly. Make routine
implementation decisions independently. Build small, verified increments.

## Version control

Use Git before moving to another app version. Commit the current verified state
and tag version checkpoints before starting changes for the next version. Keep
build outputs, local SDK configuration, native binaries and personal recordings
out of commits. Do not publish or push without user authorization.

## Product requirements

- Personal, local-only Android camera using the actual ntsc-rs Rust core:
  https://github.com/ntsc-rs/ntsc-rs. Inspiration: realVHS, with more controls and
  imported ntsc-rs presets. No ads, accounts, subscriptions or backend.
- Record playable video with synchronized audio and correct orientation. Match
  preview and export framing at 4:3. Preserve originals during development.
- Import presets through Android's system file picker. Preserve compatibility;
  report unsupported settings, never silently discard them.
- Hold W/T for smooth camcorder-style zoom with adjustable speed and controlled
  starts/stops; pinch desirable. Digital cropping is not an optical lens.
- Lock to a selected physical camera and crop for zoom/aspect ratio. No subject
  tracking or automatic reframing. Expose EIS and OIS separately where supported.
  Report inaccessible cameras/controls. Verify lens/stabilization behavior on the
  device; do not assume the wide camera is unstabilized.
- Device: Samsung Galaxy A26 SM-A266B, One UI 8.5 (user supplied), Android 16 /
  API 36 (verified over adb). Wide means 0.5x
  ultrawide. Offer user-selectable lenses; default to the widest exposed rear
  camera. Crop zoom must not switch lenses. See docs/a26-camera-capabilities.txt
  for the measured Camera2 catalog and approved recording test.
- Prefer useful shooting controls over decorative UI. Defer Play publishing,
  monetization and broad compatibility.

## Milestones

1. Inspect upstream, dependencies, licenses, preset format and Android blockers.
   Import preset; process image, then short video; compare visuals and benchmark
   on the phone. Do not equate desktop throughput with mobile throughput.
2. Camera preview/record/save/audio, orientation and 4:3; physical lens locking,
   supported stabilization controls, W/T zoom. Record first and process afterward
   if needed; clearly label whether preview shows the final effect.
3. Measure lower-resolution live preview, keep settings consistent with export,
   then investigate live processed recording. Measure dropped frames, heat,
   memory and audio drift on hardware.

## Current implementation boundary

Image/video processing uses native Kotlin plus JNI/NDK and the pinned upstream
effect crate; do not port desktop plugins or UI. Video export supports
<=60 fps, SDR BT.601/709, optional AAC copied with
timestamps, and downscaled upright H.264 MP4 within 640x480. Keep originals.
Version 0.3 uses Camera2 with a fixed directly exposed rear ID, crop-only zoom,
MediaRecorder for 640x480 H.264/AAC originals, and MediaStore Movies/VHS saving.
Rear 2 is ultrawide (1.64 mm, EIS/OIS OFF-only); rear 0 is main (4.0 mm, EIS
OFF-only, OIS ON/OFF). Both expose 8x maximum digital crop zoom and matching
640x480/960x720 preview and recorder streams. Default to rear 2, stabilizers OFF.
The current prototype rejects logical/physical stream routing on other devices
explicitly; broad compatibility is deferred. Use CameraGeometry/ZoomMotion for
4:3 crop, orientation and acceleration/deceleration; do not add zoom-ratio lens
switching. Preview is explicitly unprocessed. Originals are retained when sent
back to the existing ntsc-rs processor.
Version 0.4 removes the 15-second/901-frame caps, adds video progress/phase status
and cooperative cancellation, remembers validated preset JSON in private
SharedPreferences, and automatically publishes processed MP4s to Gallery
Movies/VHS with generated filenames. There is no video Save dialog/button.
GalleryVideos owns pending-item copy/publication and failure/cancel cleanup;
originals are never its cleanup targets. Rendering uses a per-job cache file.
Exports remain Activity-owned and foreground-oriented; keep the screen open.
Background jobs and process-death recovery are not implemented. Camera preview
and raw recording still do not include the ntsc-rs effect.
Run native tests, Android build/lint, and synthetic instrumentation tests. The
optional cameraRecord instrumentation mode activates the camera and microphone
and leaves a roughly 3-second original in Movies/VHS; use only with explicit
user readiness, as done in this session. Its real-device test passed: container
3093 ms / video track 2873 ms, 84 frames, portrait rotation=90, hold-T zoom ~1.412x, focal 1.64 mm unchanged,
reported EIS/OIS OFF; ntsc-rs export preserved AAC and A/V start offset.
Distinguish these checks from visual comparison and sustained performance.
The user subsequently reported preview/export framing, landscape orientation,
handheld zoom feel, audible sound sync and sustained heat were all fine on the
A26. Record these as user-validated; exact test durations/conditions were not
provided. Live effect-preview heat/performance remains a separate future test.

The user reports Samsung Auto Blocker returns after 30 minutes; treat USB access
as temporary. The installed app does not require USB debugging to operate.
Initial imported-preset image result: 640x427, mean 13.7 ms (10.5–17.9), 10 frames
after warm-up. User subsequently reported about 88 ms/frame for video. That video
number includes the entire pipeline, unlike the image JNI-only measurement.
Version 0.2.1 adds separate conversion and ntsc-rs/JNI timings to locate the cost.
User's stage results: total 95.5 ms/frame, YUV-to-RGB 63.1, ntsc/JNI 7.3,
RGB-to-YUV 20.4, other 4.8. Version 0.2.2 moves conversion loops into Rust over
direct codec buffers, retaining the formulas, cropping, rotation and sampling.
Kotlin conversion code exists only in androidTest as a pixel-equality reference.
Native plane validation and synthetic on-device regression tests are required
for changes to direct-buffer handling. After the update, the user reported total
18 ms/frame: YUV-to-RGB 4.5, ntsc/JNI 7.7, RGB-to-YUV 1.0, other 4.5. Approximately
5.3x faster overall than 95.5 ms. Clip dimensions/duration and preset filename
remain unspecified. This is offline throughput, not sustained live recording.
Version 0.4's one-minute synthetic 640x480/30 fps default-preset backend test
processed all 1800 frames in 69,420 ms, with AAC packets/timestamps preserved and
0 us added A/V end-offset drift. Sampled app PSS was 79–103 MiB; maximum Android
thermal status was 0. Gallery byte equality, partial-copy cancellation cleanup,
UI automatic saving, preset reopening, and source preservation tests passed.
These synthetic tests do not activate camera/mic, and restore the old saved preset.
After the v0.4 device tests, the user said they would unplug the phone and return
later, while authorizing continued coding. The main v0.4 APK was already installed.
After the user returned and disabled Auto Blocker, adb reconnected. Version
0.4-gallery-export (code 6) was verified installed. The temporary
dev.vhs.proof.test package was removed for the current Android user (0), keeping
dev.vhs.proof and all Gallery recordings.

Version 0.5 adds optional live ntsc-rs camera preview, enabled by default. Main
passes its current validated preset JSON into CameraActivity (private preferences
are a fallback). LivePreview uses the smallest exposed 4:3 YUV stream from
320–640 pixels wide; both A26 rear cameras report 320x240 and 640x480. Upright
frames run through existing native YUV conversion and actual ntsc-rs, with the
preview/export size ratio passed as upstream scale_factor unless the preset
already requests video-size scaling. Preset keys are unchanged, but reduced
resolution/random phase/color paths mean preview is approximate. ImageReader
acquireLatestImage, one worker and one pending UI frame bound latency/memory;
reader closure runs on that worker after direct-buffer access finishes. Source
camera timestamps advance the nominal 30fps effect frame index across skips.
Live stats show fps, conversion/effect/bitmap ms and skipped frames; unspecified
camera color components visibly assume 601/limited. Raw preview remains available
by disabling the checkbox. Recording still saves unprocessed originals with
sound, followed by optional full export. Live processed recording is deferred.
Live errors are reported and switch back to raw preview, finalizing a current
original if necessary. Live toggle/lens changes are disabled during recording.
Build/lint, six native tests/clippy, APK signing/16KiB zip alignment and on-device
synthetic suite passed, including ImageWriter-to-live-preview at all four
rotations. The first approved hardware attempt could not acquire live frames
because the phone was locked; no new recording was created. No automated live
camera/microphone test was completed; subsequent manual observations follow below.

The user confirmed v0.5 live preview works. Device logs during their manual test
show approximately 29–30fps in steady short windows at 320x240/240x320, usually
0 skipped frames (some slower recording/startup windows). These are observations
from the user's session, not a controlled sustained benchmark. They reported
Process last clip exits to the launcher without producing an effected video.
Investigation found no app crash and an orphan task rooted at CameraActivity,
with no MainActivity/result recipient; original Gallery rows 7411 and 7412 were
published. Version 0.5.1 (code 8) adds fallback launch of MainActivity with the
clip URI when callingActivity is null; Main routes that startup intent and the
normal activity-result path through the same processVideo method. Do not remove
these original recordings. Tests cover synthetic Gallery export by both paths.

Version 0.5.1 verification: Android build/lint, six native tests, signed/aligned
APK and synthetic device suite passed, including both activity-result export
and standalone startup export. Installed code 8. Retried the user's existing
original 7412 through the standalone startup path without activating camera/mic:
processed output 7416 (VHS-processed-1793511625235.mp4) is published in Movies/VHS,
5.141250 sec, H.264 640x480, 147 frames. All 241 AAC packet hashes matched;
audio/video packet timestamps matched within 100us. Original 7412 SHA256 before
and after: 5f0b00ca8c2749b3dc2cc37a81cdb2998dec5d47493c28b2e32a46fd1a494d0d.
Kept both source and processed Gallery videos. Removed temporary test package
for user 0 after testing. Long-session live-preview heat remains unverified.

Version 0.6 (code 9), branch v0.6-camera-controls, changes the launcher to
CameraActivity (dark platform Material theme), app label VHS Camera. MainActivity
is now unexported developer/export UI; normal camera exports hide developer
widgets and retain the camera underneath so Back returns to shooting. Settings
holds lens, preset import via shared PresetStore, live preview, EIS/OIS,
per-lens defaultZoom.<cameraId> (Float), global zoomSpeed, and Developer mode.
Default zoom loads/clamps via CameraGeometry.startingZoom on initial lens setup
and lens changes. Rotation saves current lens/zoom, live state and lastClip;
last successful published original URI is remembered in private prefs.
MediaRecorder.pause/resume keeps one file and original session; RecordingTime
controls a pause-excluding UI timer. The camera/preview remain active while
paused; leaving the Activity still finalizes the current original. Recorder
pause/resume failure stops/finalizes with a visible error. Settings/gallery/lens
changes are disabled during recording, including paused recording. Originals
remain clean; live processed recording and background recording are deferred.
Synthetic instrumentation adds EGL-generated video-only pause/resume and stopping
while paused; it never activates camera/mic. cameraRecord=true now performs a
~6-second wall-time recording with a 2-second pause (~4 seconds of footage),
preceded by ~5 seconds of preview. Require fresh user readiness; current async
readiness/connect questions have not yet been answered. The A26 disconnected
before install. 0.6 build/lint, six native tests/clippy, signing/alignment and
host JVM assertions for compiled startingZoom/RecordingTime passed. Device
synthetic recorder tests, audio pause/resume and visual inspection are pending.
Do not claim 0.6 installed or device-verified until those actually run.
