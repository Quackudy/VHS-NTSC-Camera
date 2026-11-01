use jni::{
    JNIEnv,
    objects::{JIntArray, JObject, JString},
    sys::{jfloat, jint, jintArray},
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
            SettingKind::Percentage { .. } => number.is_some_and(|n| (0.0..=1.0).contains(&n)),
            SettingKind::FloatRange { range, .. } => number.is_some_and(|n| {
                // Upstream stores f32 and emits their shortest decimal representation.
                range.contains(&(n as f32))
            }),
            SettingKind::IntRange { range } => number.is_some_and(|n| {
                n.fract() == 0.0
                    && (f64::from(*range.start())..=f64::from(*range.end())).contains(&n)
            }),
        };
        if !valid {
            return Err(format!("Invalid type, option or range for setting: {key}"));
        }
    }
    // Keep upstream's legacy defaults and preset semantics after strict validation.
    list.from_json(json).map_err(|e| e.to_string())
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
    let mut rgb = Vec::with_capacity(pixels.len() * 3);
    for &pixel in pixels {
        rgb.extend_from_slice(&[(pixel >> 16) as u8, (pixel >> 8) as u8, pixel as u8]);
    }
    effect.apply_effect_to_buffer::<Rgb, u8>(
        ntsc_rs::ctx::global(),
        (width, height),
        &mut rgb,
        frame,
        [scale, scale],
    );
    Ok(rgb
        .as_chunks::<3>()
        .0
        .iter()
        .map(|p| {
            (0xff000000u32 | (u32::from(p[0]) << 16) | (u32::from(p[1]) << 8) | u32::from(p[2]))
                as i32
        })
        .collect())
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
) {
    boundary(&mut env, |env| {
        parse_preset(&read_preset(env, &preset)?).map(|_| ())
    });
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
        let output = process_scaled(
            &input,
            width as usize,
            height as usize,
            frame as usize,
            &preset,
            preview_scale,
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
    fn rejects_unsupported_and_unsafe_presets() {
        for json in [
            r#"{"version":2}"#,
            r#"{"version":1,"future_setting":true}"#,
            r#"{"version":1,"use_field":999}"#,
            r#"{"version":1,"use_field":0.5}"#,
            r#"{"version":1,"snow_intensity":-1}"#,
            r#"{"version":1,"snow_intensity":"0.2"}"#,
            r#"{"version":1,"vhs_settings":{}}"#,
            "[]",
            "not JSON",
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
