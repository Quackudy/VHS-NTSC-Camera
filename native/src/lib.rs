use jni::{
    JNIEnv,
    objects::{JIntArray, JObject, JString},
    sys::{jboolean, jfloat, jint, jintArray, jstring},
};
use ntsc_rs::{
    NtscEffect,
    settings::{SettingKind, SettingsList},
    yiq_fielding::Rgb,
};
use serde_json::Value;
use std::panic::{AssertUnwindSafe, catch_unwind};

mod yuv;

const MAX_PIXELS: usize = 640 * 480;
const MAX_PRESET_BYTES: usize = 64 * 1024;
const RETIRED_KEYS: [&str; 2] = [
    "video_scanline_phase_shift",
    "video_scanline_phase_shift_offset",
];

pub fn parse_preset(json: &str) -> Result<NtscEffect, String> {
    if json.len() > MAX_PRESET_BYTES {
        return Err("Preset exceeds 64 KiB".into());
    }
    let value: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let map = value.as_object().ok_or("Preset must be a JSON object")?;
    if map.get("version").and_then(Value::as_f64) != Some(1.0) {
        return Err("Only ntsc-rs version-1 JSON presets are supported".into());
    }
    let list = SettingsList::<NtscEffect>::new();
    for (key, value) in map {
        if key == "version" {
            continue;
        }
        if RETIRED_KEYS.contains(&key.as_str()) {
            if !value.as_f64().is_some_and(|n| n.is_finite()) {
                return Err(format!("Invalid numeric value for retired setting: {key}"));
            }
            continue;
        }
        let descriptor = list
            .all_descriptors()
            .find(|d| d.id.name == key)
            .ok_or_else(|| format!("Unsupported setting: {key}"))?;
        let number = value.as_f64().filter(|n| n.is_finite());
        let valid = match &descriptor.kind {
            SettingKind::Boolean | SettingKind::Group { .. } => value.is_boolean(),
            SettingKind::Enumeration { options } => {
                number.is_some_and(|n| options.iter().any(|o| n == f64::from(o.index)))
            }
            SettingKind::Percentage { .. } | SettingKind::FloatRange { .. } => {
                number.is_some_and(|n| (n as f32).is_finite())
            }
            SettingKind::IntRange { .. } => number.is_some_and(|n| {
                n.fract() == 0.0 && (f64::from(i32::MIN)..=f64::from(i32::MAX)).contains(&n)
            }),
        };
        if !valid {
            return Err(format!("Invalid type, option or range for setting: {key}"));
        }
    }
    // Upstream applies legacy defaults and clamps supported numeric settings to its UI ranges.
    list.from_json(json).map_err(|e| e.to_string())
}

/// Report every known compatibility adjustment; arbitrary unknown keys remain errors.
pub fn preset_warnings(json: &str) -> Result<String, String> {
    parse_preset(json)?;
    let value: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let map = value.as_object().ok_or("Preset must be a JSON object")?;
    let mut warnings = Vec::new();
    let retired: Vec<_> = RETIRED_KEYS
        .into_iter()
        .filter(|key| map.contains_key(*key))
        .collect();
    if !retired.is_empty() {
        warnings.push(format!(
            "Retired settings not applied by this ntsc-rs core: {}.",
            retired.join(", ")
        ));
    }
    let list = SettingsList::<NtscEffect>::new();
    for descriptor in list.all_descriptors() {
        let key = descriptor.id.name;
        let Some(number) = map.get(key).and_then(Value::as_f64) else {
            continue;
        };
        let adjusted = match &descriptor.kind {
            SettingKind::Percentage { .. } => f64::from((number as f32).clamp(0.0, 1.0)),
            SettingKind::FloatRange { range, .. } => {
                f64::from((number as f32).clamp(*range.start(), *range.end()))
            }
            SettingKind::IntRange { range } => {
                f64::from((number as i32).clamp(*range.start(), *range.end()))
            }
            _ => continue,
        };
        // Ignore normal f64-to-f32 rounding; warn only when upstream actually clamps.
        let original = if matches!(&descriptor.kind, SettingKind::IntRange { .. }) {
            number
        } else {
            f64::from(number as f32)
        };
        if adjusted != original {
            warnings.push(format!(
                "{key}: {number} adjusted to {adjusted}, matching this core's supported range."
            ));
        }
    }
    Ok(warnings.join("\n"))
}

pub fn process(
    pixels: &[i32],
    width: usize,
    height: usize,
    frame: usize,
    preset: &str,
) -> Result<Vec<i32>, String> {
    process_scaled(pixels, width, height, frame, preset, 1.0)
}

fn process_scaled(
    pixels: &[i32],
    width: usize,
    height: usize,
    frame: usize,
    preset: &str,
    preview_scale: f32,
) -> Result<Vec<i32>, String> {
    process_frame(pixels, width, height, frame, preset, preview_scale, false)
}

fn process_frame(
    pixels: &[i32],
    width: usize,
    height: usize,
    frame: usize,
    preset: &str,
    preview_scale: f32,
    clean_edges: bool,
) -> Result<Vec<i32>, String> {
    if !preview_scale.is_finite() || preview_scale <= 0.0 || preview_scale > 1.0 {
        return Err("Preview scale must be within (0, 1]".into());
    }
    if width < 2
        || height < 2
        || width > 640
        || height > 640
        || width * height > MAX_PIXELS
        || pixels.len() != width * height
    {
        return Err(
            "Expected an RGB image up to 640 pixels per side and 307200 pixels total".into(),
        );
    }
    let effect = parse_preset(preset)?;
    // Upstream already scales by frame height when the preset requests it.
    let scale = if effect.scale.enabled && effect.scale.settings.scale_with_video_size {
        1.0
    } else {
        preview_scale
    };
    // Extend the source rather than zooming/cropping away its field of view.
    let side = if clean_edges { 64 } else { 0 };
    let bottom = if clean_edges { 32 } else { 0 };
    let padded_width = width + side * 2;
    let padded_height = height + bottom;
    let mut rgb = Vec::with_capacity(padded_width * padded_height * 3);
    for y in 0..padded_height {
        for x in 0..padded_width {
            let pixel = pixels[y.min(height - 1) * width + x.saturating_sub(side).min(width - 1)];
            rgb.extend_from_slice(&[(pixel >> 16) as u8, (pixel >> 8) as u8, pixel as u8]);
        }
    }
    let scale =
        if clean_edges && effect.scale.enabled && effect.scale.settings.scale_with_video_size {
            scale * height as f32 / padded_height as f32
        } else {
            scale
        };
    effect.apply_effect_to_buffer::<Rgb, u8>(
        ntsc_rs::ctx::global(),
        (padded_width, padded_height),
        &mut rgb,
        frame,
        [scale, scale],
    );
    let mut output = Vec::with_capacity(pixels.len());
    for row in rgb.chunks_exact(padded_width * 3).take(height) {
        for p in row[side * 3..(side + width) * 3].as_chunks::<3>().0 {
            output.push(
                (0xff000000u32 | (u32::from(p[0]) << 16) | (u32::from(p[1]) << 8) | u32::from(p[2]))
                    as i32,
            );
        }
    }
    Ok(output)
}

fn boundary<T>(env: &mut JNIEnv, call: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> Option<T> {
    let result = catch_unwind(AssertUnwindSafe(|| call(env)))
        .unwrap_or_else(|_| Err("Native processing failed; try another preset".into()));
    match result {
        Ok(value) => Some(value),
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            None
        }
    }
}

fn read_preset(env: &mut JNIEnv, preset: &JString) -> Result<String, String> {
    env.get_string(preset)
        .map(Into::into)
        .map_err(|e| e.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_vhs_proof_Native_validatePreset(
    mut env: JNIEnv,
    _this: JObject,
    preset: JString,
) -> jstring {
    boundary(&mut env, |env| {
        let warnings = preset_warnings(&read_preset(env, &preset)?)?;
        env.new_string(warnings)
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    })
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_vhs_proof_Native_process(
    mut env: JNIEnv,
    _this: JObject,
    pixels: JIntArray,
    width: jint,
    height: jint,
    frame: jint,
    preset: JString,
    preview_scale: jfloat,
    clean_edges: jboolean,
) -> jintArray {
    boundary(&mut env, |env| {
        if width < 2 || height < 2 || frame < 0 {
            return Err("Invalid frame dimensions or frame number".into());
        }
        let len = env.get_array_length(&pixels).map_err(|e| e.to_string())? as usize;
        if len > MAX_PIXELS {
            return Err("Image exceeds proof-of-concept size limit".into());
        }
        let mut input = vec![0; len];
        env.get_int_array_region(&pixels, 0, &mut input)
            .map_err(|e| e.to_string())?;
        let preset = read_preset(env, &preset)?;
        let output = process_frame(
            &input,
            width as usize,
            height as usize,
            frame as usize,
            &preset,
            preview_scale,
            clean_edges != 0,
        )?;
        let result = env.new_int_array(len as i32).map_err(|e| e.to_string())?;
        env.set_int_array_region(&result, 0, &output)
            .map_err(|e| e.to_string())?;
        Ok(result.into_raw())
    })
    .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preview_scale_validation_and_export_equivalence() {
        let pixels = vec![0xff8899aau32 as i32; 16 * 12];
        let preset = r#"{"version":1}"#;
        assert_eq!(
            process(&pixels, 16, 12, 0, preset).unwrap(),
            process_scaled(&pixels, 16, 12, 0, preset, 1.0).unwrap()
        );
        assert_eq!(
            process_scaled(&pixels, 16, 12, 0, preset, 0.5)
                .unwrap()
                .len(),
            pixels.len()
        );
        let auto_scale = r#"{"version":1,"scale_settings":true,"scale_with_video_size":true}"#;
        assert_eq!(
            process(&pixels, 16, 12, 0, auto_scale).unwrap(),
            process_scaled(&pixels, 16, 12, 0, auto_scale, 0.5).unwrap()
        );
        for scale in [0.0, -1.0, 1.1, f32::NAN, f32::INFINITY] {
            assert!(process_scaled(&pixels, 16, 12, 0, preset, scale).is_err());
        }
    }

    #[test]
    fn accepts_upstream_presets_and_legacy_defaults() {
        let list = SettingsList::<NtscEffect>::new();
        let json = list.to_json_string(&NtscEffect::default()).unwrap();
        parse_preset(&json).unwrap();
        let minimal = parse_preset(r#"{"version":1}"#).unwrap();
        let reference = list.from_json(r#"{"version":1}"#).unwrap();
        assert_eq!(
            list.to_json_string(&minimal).unwrap(),
            list.to_json_string(&reference).unwrap()
        );
    }

    #[test]
    fn edge_extension_matches_upstream_then_returns_original_frame_bounds() {
        let (width, height) = (64, 48);
        let pixels: Vec<i32> = (0..width * height)
            .map(|i| (0xff000000u32 | (i as u32 * 777)) as i32)
            .collect();
        let preset = r#"{"version":1}"#;
        let mut extended = Vec::new();
        for y in 0..height + 32 {
            for x in 0usize..width + 128 {
                extended
                    .push(pixels[y.min(height - 1) * width + x.saturating_sub(64).min(width - 1)]);
            }
        }
        for scale in [1.0, 0.5] {
            let reference =
                process_scaled(&extended, width + 128, height + 32, 3, preset, scale).unwrap();
            let expected: Vec<i32> = reference
                .chunks_exact(width + 128)
                .take(height)
                .flat_map(|row| row[64..64 + width].iter().copied())
                .collect();
            assert_eq!(
                process_frame(&pixels, width, height, 3, preset, scale, true).unwrap(),
                expected
            );
        }
    }

    #[test]
    fn rejects_unsupported_and_unsafe_presets() {
        for json in [
            r#"{"version":2}"#,
            r#"{"version":1,"future_setting":true}"#,
            r#"{"version":1,"use_field":999}"#,
            r#"{"version":1,"use_field":0.5}"#,
            r#"{"version":1,"snow_intensity":1e100}"#,
            r#"{"version":1,"snow_intensity":"0.2"}"#,
            r#"{"version":1,"vhs_settings":{}}"#,
            "[]",
            "not JSON",
            "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj",
        ] {
            assert!(parse_preset(json).is_err(), "accepted {json}");
        }
        assert!(parse_preset(&" ".repeat(MAX_PRESET_BYTES + 1)).is_err());
    }

    #[test]
    fn accepts_decimal_float_range_endpoints() {
        for descriptor in SettingsList::<NtscEffect>::new().all_descriptors() {
            if let SettingKind::FloatRange { range, .. } = &descriptor.kind {
                for endpoint in [range.start(), range.end()] {
                    let json = format!(r#"{{"version":1,"{}":{endpoint}}}"#, descriptor.id.name);
                    parse_preset(&json).unwrap_or_else(|error| panic!("{json}: {error}"));
                }
            }
        }
    }

    #[test]
    fn legacy_keys_and_clamps_match_upstream_with_explicit_warnings() {
        let json = r#"{"version":1,"video_scanline_phase_shift":2,"video_scanline_phase_shift_offset":0,"snow_intensity":-1,"head_switching_height":9999}"#;
        let list = SettingsList::<NtscEffect>::new();
        assert_eq!(
            list.to_json_string(&parse_preset(json).unwrap()).unwrap(),
            list.to_json_string(&list.from_json(json).unwrap()).unwrap()
        );
        let warnings = preset_warnings(json).unwrap();
        for key in [
            "video_scanline_phase_shift",
            "video_scanline_phase_shift_offset",
            "snow_intensity",
            "head_switching_height",
        ] {
            assert!(warnings.contains(key), "missing warning for {key}");
        }
        assert!(preset_warnings(r#"{"version":1}"#).unwrap().is_empty());
        assert!(preset_warnings(r#"{"version":1,"unknown_setting":1}"#).is_err());
    }

    #[test]
    fn different_presets_change_export_and_scaled_preview_pixels() {
        let input = vec![0xff778899u32 as i32; 320 * 240];
        for scale in [1.0, 0.5] {
            let clean = process_scaled(
                &input,
                320,
                240,
                0,
                r#"{"version":1,"snow_intensity":0}"#,
                scale,
            )
            .unwrap();
            let noisy = process_scaled(
                &input,
                320,
                240,
                0,
                r#"{"version":1,"snow_intensity":1}"#,
                scale,
            )
            .unwrap();
            let changed = clean.iter().zip(&noisy).filter(|(a, b)| a != b).count();
            assert!(
                changed > input.len() / 100,
                "preset change affected only {changed} pixels"
            );
        }
    }

    #[test]
    fn matches_upstream_rgb_pipeline_across_frames() {
        let input: Vec<i32> = (0..64 * 48)
            .map(|i| (0xff000000u32 | (((i % 64) * 4) << 16) | (((i / 64) * 5) << 8) | 128) as i32)
            .collect();
        let preset = SettingsList::<NtscEffect>::new()
            .to_json_string(&NtscEffect::default())
            .unwrap();
        for frame in [0, 1, 29] {
            let output = process(&input, 64, 48, frame, &preset).unwrap();
            let mut reference: Vec<u8> = input
                .iter()
                .flat_map(|p| [(p >> 16) as u8, (p >> 8) as u8, *p as u8])
                .collect();
            parse_preset(&preset)
                .unwrap()
                .apply_effect_to_buffer::<Rgb, u8>(
                    ntsc_rs::ctx::global(),
                    (64, 48),
                    &mut reference,
                    frame,
                    [1.0, 1.0],
                );
            for (actual, expected) in output.iter().zip(reference.as_chunks::<3>().0.iter()) {
                assert_eq!(
                    [(*actual >> 16) as u8, (*actual >> 8) as u8, *actual as u8],
                    *expected
                );
                assert_eq!((*actual as u32) >> 24, 255);
            }
            assert_ne!(output, input);
        }
        assert!(process(&input, 0, 48, 0, &preset).is_err());
        assert!(process(&input, 64, 47, 0, &preset).is_err());
    }
}
