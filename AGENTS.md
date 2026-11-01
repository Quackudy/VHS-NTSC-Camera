# VHS camera project

The developer uses CachyOS/Linux, can program, and is new to Android. Explain
Android setup, builds, installation and platform decisions clearly. Make routine
implementation decisions independently. Build small, verified increments.

## Phone file access

Ask before browsing or reading user files on the phone through ADB. USB debugging
access is not blanket permission to inspect personal storage. Use workspace files,
existing explicitly obtained debug copies and test-owned fixtures. Do not read
Downloads, other phone folders, or preference XML through shell commands without
asking. The user raised this concern and accepted continued fixing under this rule.

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
preceded by ~5 seconds of preview. Require fresh user readiness for future
camera/mic tests. On reconnect, the user explicitly approved this test and it
passed. Version 0.6 code 9 is installed. The default synthetic suite passed,
including EGL video-only pause/resume and stop-while-paused checks. Default 1.5x
zoom saved/reloaded via the real camera controls; the prior preference was restored.
The actual test original is Gallery 7424, VHS-original-1793520284752.mp4:
121 video frames, video endpoint 4200ms, 640x480 rotation90, AAC, 2s pause omitted,
hold-T ending crop~2.119x, rear 2 focal1.64/EIS0/OIS0 unchanged. Its MP4 container
is 4416ms including audio timing/tail. The normal Process last clip button then
published Gallery 7425, VHS-processed-1793520432532.mp4, upright360x480. Back
returned to the camera. All 207 AAC packet hashes and audio/video timestamps
matched within100us; original SHA256 unchanged. Max source timestamp gaps were
video89.94ms/audio21.33ms (no2s gap). This does not verify perceptual speech/clap
sync or sustained frame continuity. Preview sample:28.7fps,13.7ms/frame,2skipped,
PSS102–109MiB,thermal0 during only5s. Preserve both Gallery items.
Actual portrait/landscape ready UI and 1.3x font captures plus landscape Settings
passed the independent Impeccable material-issue review (disposition ship for
those captures). Record/pause appearance was not screenshot-tested. Screenshots
are ignored in app/build/review. Original system accelerometer_rotation0,
user_rotation0,font_scale1.0 were restored; test package removed for user0.
Build/lint, six native tests/clippy, signing/alignment and host zoom/timer checks
passed. Long-session live heat/stability and perceptual pause-join sync remain
manual tests. No new source changes were required after the phone tests.

Version 0.6.1 (code10, branch v0.6.1-preset-import) fixes imports/compatibility.
Previously the phone had no remembered preset and was using defaults. The two
known retired keys video_scanline_phase_shift and video_scanline_phase_shift_offset
no longer reject an otherwise valid preset: they are explicitly reported as not
applied by the pinned core. Unknown other fields/invalid types or enums still
reject. Numeric ranges now follow upstream clamping with each adjustment warned.
Native.validatePreset returns a JNI String of warnings; imported JSON stays intact.
PresetStore persists presetName/presetWarnings with JSON; Camera and Developer
mode show filename, visible rejection or compatibility dialogs, and retain prior
selection on failure. Session snapshots reconfigure if an import completes during
Camera2 startup; stale frames cannot display under the new selection.
Eight Rust tests/clippy, build/lint, signing/alignment, the default synthetic A26
suite (PDF/UTF8/size/unknown rejection and restored warnings included), and the
explicitly authorized cameraPreset=true preview-only test passed. The latter
forces import completion before onConfigured, verifies the new engine JSON,
legacy compatibility notice and PDF rejection, then restores all prior preset
metadata. No camera recording/microphone capture occurred. Existing locally
obtained copies of both reported PC presets matched upstream's parsed settings
exactly; the agent-created debug copies were then removed. User originals on
phone were never modified. Do not browse/read more user phone files without
asking; the earlier file access concern applies persistently.
The two diagnostic presets and README are in docs/preset-checks and were already
placed in phone Downloads/VHS-preset-checks before the privacy concern. Do not
arbitrarily select them as the user's permanent preset. A subsequent user request
asks to use workspace app_image.jpg as app icon after completing this preset fix.

Version 0.6.2 (code11, branch v0.6.2-app-icon) follows verified/tagged v0.6.1.
The user explicitly supplied workspace app_image.jpg and requested it as the app
icon after preset fixing. It is a 1024-square duck/camcorder illustration. Use
that original asset, not generated replacements. Legacy density launcher PNGs
(48/72/96/144/192) and adaptive foreground432 (art264 centered) derive from it;
background #3DBCE2 sampled from its corner. Manifest icon/roundIcon both reference
mipmap/ic_launcher. Original source image is kept. Build/lint, signing/alignment,
aapt icon-resource wiring passed; code11 installed on A26. No engine changes
from the fully tested v0.6.1 checkpoint. Test package removed for user0. No new
phone user-file browsing/reading occurred after the privacy boundary was set.
The final supported preset state/settings were restored after fixture tests.

Version 0.6.3 (code12), branch v0.6.3-reset-preset, adds Reset preset to default
in Camera Settings and Developer mode. PresetStore.DEFAULT_JSON is exactly
{"version":1}, the original pre-import legacy settings, not NtscEffect::default().
PresetStore.reset removes only preset/presetName/presetWarnings, returns a default
ImportedPreset and reports persistence failure. Camera routes imports and reset
through shared changePreset/applyImportedPreset so the existing session-snapshot
race guard handles live updates; Developer mode clears its processed image and
updates current export settings. Tests add reset/reopen and unrelated preference
preservation to the default suite, and live-engine reset to optional cameraPreset.
Build/lint, APK signing and 16KiB zip alignment passed for 0.6.3. Code 12 was
installed on the A26 and the default synthetic instrumentation suite passed,
including reset/reopen and unrelated-preference preservation. The optional
camera-preview reset test has not run; the synthetic suite did not activate
camera or microphone. The helper test package was removed for user 0. No phone
files were browsed.

Version 0.6.4 (code13), branch v0.6.4-cleaner-default, defines a VHS Camera
default profile by starting from ntsc-rs legacy defaults and setting only
head_switching=false and vhs_edge_wave_enabled=false. The former removes the
head-switching tear at the bottom; the latter removes horizontal edge waviness.
Other legacy grain/color/effect values are preserved. Reset and cold-launch
fallback both use this profile; the UI labels it "VHS Camera defaults" to avoid
misrepresenting it as the exact upstream preset. A Rust equality test checks the
full parsed effect differs from upstream legacy defaults only in those two
enabled flags. All nine native tests and Clippy, Android build/lint, APK signing/16KiB
alignment, and the default on-device synthetic suite passed. Code 13 is
installed on the A26; the helper package was removed for user 0. No
camera/microphone test or phone file browsing occurred. Visual comparison of
live preview and processed export with the new default is still pending.

Version 0.6.5 (code14), branch v0.6.5-frame-edges, reverts 0.6.4's preset
parameter edits at the user's explicit request. DEFAULT_JSON is again exactly
{"version":1}; head switching and edge wave retain the original legacy values.
An authorized live-preview screenshot confirmed the unwanted bottom noisy band
and side striped filter-start transient while the app-default label was active.
The pinned upstream source explicitly notes the VHS zero-initialized filter's
side line; tracking noise operates on bottom rows. Native.process adds cleanEdges,
defaulting to true only for the exact built-in default JSON. This extends source
pixels 64 columns on each side and 32 rows below, applies the actual unmodified
core, and extracts the original frame bounds. No source FOV/zoom is lost; preview
and export share the path. Other preset JSON retains the unpadded upstream path.
Host tests compare padding/cropping with an explicit upstream frame reference;
All nine native tests/Clippy, Android build/lint, signing/16KiB alignment and
the on-device synthetic suite passed. Code 14 is installed and the helper test
package removed for user 0. The screen fell asleep before the after screenshot;
visual verification is waiting for the user to unlock it. No footage was recorded.
