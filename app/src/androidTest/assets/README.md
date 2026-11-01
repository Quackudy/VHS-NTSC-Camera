# Synthetic video fixtures

Generated locally with FFmpeg 8 from its test-pattern and sine-wave generators.
No real footage, personal recordings or downloaded media. FFmpeg is a development
tool only; it is not linked into or shipped with the Android app. These assets
ship only in the separate test APK.

Run from the repository root to reproduce:

```bash
ffmpeg -f lavfi -i testsrc2=size=320x240:rate=30:duration=1 \
  -f lavfi -i sine=frequency=880:sample_rate=48000:duration=1 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p \
  -color_primaries bt709 -colorspace bt709 -color_trc bt709 \
  -c:a aac -b:a 64k -movflags +faststart -y app/src/androidTest/assets/clip.mp4
ffmpeg -display_rotation 90 -i app/src/androidTest/assets/clip.mp4 \
  -c copy -y app/src/androidTest/assets/rotated.mp4
ffmpeg -itsoffset 0.12 -i app/src/androidTest/assets/clip.mp4 \
  -i app/src/androidTest/assets/clip.mp4 -map 0:v:0 -map 1:a:0 \
  -c copy -y app/src/androidTest/assets/offset.mp4
ffmpeg -i app/src/androidTest/assets/clip.mp4 -map 0:v:0 -an \
  -c copy -y app/src/androidTest/assets/silent.mp4
```
