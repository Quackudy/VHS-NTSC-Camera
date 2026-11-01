use jni::{
    JNIEnv,
    objects::{JByteBuffer, JIntArray, JObject},
    sys::{jboolean, jint, jintArray},
};

use super::{MAX_PIXELS, boundary};

struct Plane {
    ptr: *mut u8,
    len: usize,
    row_stride: usize,
    pixel_stride: usize,
}

impl Plane {
    fn validate(&self, width: usize, height: usize) -> Result<(), String> {
        if width == 0 || height == 0 || self.row_stride == 0 || self.pixel_stride == 0 {
            return Err("Invalid YUV plane dimensions or strides".into());
        }
        let row = (width - 1)
            .checked_mul(self.pixel_stride)
            .and_then(|v| v.checked_add(1));
        let end = row.and_then(|row| (height - 1).checked_mul(self.row_stride)?.checked_add(row));
        if self.ptr.is_null()
            || row.is_none_or(|row| row > self.row_stride)
            || end.is_none_or(|end| end > self.len)
        {
            return Err("YUV plane is too short for its dimensions and strides".into());
        }
        Ok(())
    }

    // Call only after validate covers every coordinate; the JNI buffers stay alive during the call.
    unsafe fn read(&self, x: usize, y: usize) -> u8 {
        unsafe {
            self.ptr
                .add(y * self.row_stride + x * self.pixel_stride)
                .read()
        }
    }

    unsafe fn write(&self, x: usize, y: usize, value: i32) {
        // Raw writes allow U/V views to share an interleaved buffer without aliasing &mut slices.
        unsafe {
            self.ptr
                .add(y * self.row_stride + x * self.pixel_stride)
                .write(value.clamp(0, 255) as u8)
        }
    }
}

fn planes(
    env: &mut JNIEnv,
    buffers: [&JByteBuffer; 3],
    strides: &JIntArray,
    writable: bool,
) -> Result<[Plane; 3], String> {
    if env.get_array_length(strides).map_err(|e| e.to_string())? != 6 {
        return Err("Expected row and pixel strides for three planes".into());
    }
    let mut values = [0; 6];
    env.get_int_array_region(strides, 0, &mut values)
        .map_err(|e| e.to_string())?;
    let mut result = Vec::with_capacity(3);
    for (index, buffer) in buffers.into_iter().enumerate() {
        if values[2 * index] <= 0 || values[2 * index + 1] <= 0 {
            return Err("YUV plane strides must be positive".into());
        }
        if writable
            && env
                .call_method(buffer, "isReadOnly", "()Z", &[])
                .and_then(|value| value.z())
                .map_err(|e| e.to_string())?
        {
            return Err("Encoder YUV plane is read-only".into());
        }
        result.push(Plane {
            ptr: env
                .get_direct_buffer_address(buffer)
                .map_err(|_| "YUV planes must use direct byte buffers")?,
            len: env
                .get_direct_buffer_capacity(buffer)
                .map_err(|e| e.to_string())?,
            row_stride: values[2 * index] as usize,
            pixel_stride: values[2 * index + 1] as usize,
        });
    }
    result
        .try_into()
        .map_err(|_| "Expected three planes".into())
}

fn output_size(width: jint, height: jint) -> Result<(usize, usize), String> {
    if !(2..=640).contains(&width)
        || !(2..=640).contains(&height)
        || width as usize * height as usize > MAX_PIXELS
    {
        return Err("Invalid conversion output dimensions".into());
    }
    Ok((width as usize, height as usize))
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_vhs_proof_Native_yuvToRgb(
    mut env: JNIEnv,
    _this: JObject,
    y: JByteBuffer,
    u: JByteBuffer,
    v: JByteBuffer,
    strides: JIntArray,
    left: jint,
    top: jint,
    width: jint,
    height: jint,
    out_width: jint,
    out_height: jint,
    rotation: jint,
    bt709: jboolean,
    full_range: jboolean,
) -> jintArray {
    boundary(&mut env, |env| {
        let (ow, oh) = output_size(out_width, out_height)?;
        if !(0..=4096).contains(&left)
            || !(0..=4096).contains(&top)
            || !(1..=4096).contains(&width)
            || !(1..=4096).contains(&height)
            || ![0, 90, 180, 270].contains(&rotation)
        {
            return Err("Invalid YUV crop or rotation".into());
        }
        let planes = planes(env, [&y, &u, &v], &strides, false)?;
        let (left, top, width, height) =
            (left as usize, top as usize, width as usize, height as usize);
        planes[0].validate(left + width, top + height)?;
        for plane in &planes[1..] {
            plane.validate((left + width).div_ceil(2), (top + height).div_ceil(2))?;
        }
        let (rw, rh) = if rotation % 180 == 0 {
            (width, height)
        } else {
            (height, width)
        };
        let chroma = if full_range != 0 { 1.0 } else { 255.0 / 224.0 };
        let (cr, cgu, cgv, cb) = if bt709 != 0 {
            (1.5748, 0.187324, 0.468124, 1.8556)
        } else {
            (1.402, 0.344136, 0.714136, 1.772)
        };
        let mut rgb = Vec::with_capacity(ow * oh);
        // shortcut: nearest-neighbor scaling; add filtered resampling when tuning export quality.
        for row in 0..oh {
            for col in 0..ow {
                let (x, y) = (col * rw / ow, row * rh / oh);
                let (sx, sy) = match rotation {
                    90 => (y, height - 1 - x),
                    180 => (width - 1 - x, height - 1 - y),
                    270 => (width - 1 - y, x),
                    _ => (x, y),
                };
                let (sx, sy) = (left + sx, top + sy);
                // Bounds validated above; Java references retain the live codec image planes.
                let (luma, u, v) = unsafe {
                    (
                        f64::from(planes[0].read(sx, sy)),
                        f64::from(planes[1].read(sx / 2, sy / 2)) - 128.0,
                        f64::from(planes[2].read(sx / 2, sy / 2)) - 128.0,
                    )
                };
                let yy = if full_range != 0 {
                    luma
                } else {
                    (luma - 16.0) * 255.0 / 219.0
                };
                let red = (yy + cr * chroma * v).round().clamp(0.0, 255.0) as i32;
                let green = (yy - cgu * chroma * u - cgv * chroma * v)
                    .round()
                    .clamp(0.0, 255.0) as i32;
                let blue = (yy + cb * chroma * u).round().clamp(0.0, 255.0) as i32;
                rgb.push((0xff000000u32 as i32) | (red << 16) | (green << 8) | blue);
            }
        }
        let array = env
            .new_int_array(rgb.len() as i32)
            .map_err(|e| e.to_string())?;
        env.set_int_array_region(&array, 0, &rgb)
            .map_err(|e| e.to_string())?;
        Ok(array.into_raw())
    })
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_vhs_proof_Native_rgbToYuv(
    mut env: JNIEnv,
    _this: JObject,
    rgb: JIntArray,
    width: jint,
    height: jint,
    y: JByteBuffer,
    u: JByteBuffer,
    v: JByteBuffer,
    strides: JIntArray,
) {
    boundary(&mut env, |env| {
        let (width, height) = output_size(width, height)?;
        if width % 2 != 0
            || height % 2 != 0
            || env.get_array_length(&rgb).map_err(|e| e.to_string())? as usize != width * height
        {
            return Err("Expected RGB pixels with even YUV420 dimensions".into());
        }
        let planes = planes(env, [&y, &u, &v], &strides, true)?;
        planes[0].validate(width, height)?;
        for plane in &planes[1..] {
            plane.validate(width / 2, height / 2)?;
        }
        let mut input = vec![0; width * height];
        env.get_int_array_region(&rgb, 0, &mut input)
            .map_err(|e| e.to_string())?;
        for row in (0..height).step_by(2) {
            for col in (0..width).step_by(2) {
                let (mut rs, mut gs, mut bs) = (0, 0, 0);
                for dy in 0..2 {
                    for dx in 0..2 {
                        let pixel = input[(row + dy) * width + col + dx];
                        let (r, g, b) = ((pixel >> 16) & 255, (pixel >> 8) & 255, pixel & 255);
                        // Writable buffers remain alive and all plane bounds were checked above.
                        unsafe {
                            planes[0].write(
                                col + dx,
                                row + dy,
                                ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16,
                            );
                        }
                        rs += r;
                        gs += g;
                        bs += b;
                    }
                }
                let (r, g, b) = (rs / 4, gs / 4, bs / 4);
                unsafe {
                    planes[1].write(
                        col / 2,
                        row / 2,
                        ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128,
                    );
                    planes[2].write(
                        col / 2,
                        row / 2,
                        ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128,
                    );
                }
            }
        }
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_last_pixel_without_requiring_last_row_padding() {
        let mut bytes = [0; 13];
        let plane = Plane {
            ptr: bytes.as_mut_ptr(),
            len: bytes.len(),
            row_stride: 8,
            pixel_stride: 2,
        };
        assert!(plane.validate(3, 2).is_ok());
        assert!(plane.validate(4, 2).is_err());
        assert!(plane.validate(3, 3).is_err());
        assert!(plane.validate(0, 2).is_err());
        assert!(plane.validate(usize::MAX, 2).is_err());
        assert!(output_size(640, 480).is_ok());
        assert!(output_size(640, 640).is_err());
        assert!(output_size(-1, 480).is_err());
    }
}
