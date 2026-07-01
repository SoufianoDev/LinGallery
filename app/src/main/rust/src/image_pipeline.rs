use std::collections::HashSet;
use std::io::Cursor;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use image::imageops::FilterType;
use image::ImageReader;
use image::RgbaImage;
use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;

use crate::lin_log;

static LATEST_VIEWER_REQUEST: AtomicU64 = AtomicU64::new(0);
static CANCELLED_REQUESTS: OnceLock<Mutex<HashSet<u64>>> = OnceLock::new();

fn cancelled_requests() -> &'static Mutex<HashSet<u64>> {
    CANCELLED_REQUESTS.get_or_init(|| Mutex::new(HashSet::new()))
}

fn is_cancelled(request_id: u64) -> bool {
    cancelled_requests()
        .lock()
        .map(|set| set.contains(&request_id))
        .unwrap_or(true)
}

fn is_stale(request_id: u64, viewer: bool) -> bool {
    is_cancelled(request_id)
        || (viewer && LATEST_VIEWER_REQUEST.load(Ordering::Acquire) != request_id)
}

fn mark_cancelled(request_id: u64) {
    if let Ok(mut set) = cancelled_requests().lock() {
        set.insert(request_id);
    }
}

fn compute_fit(source_w: u32, source_h: u32, target_w: u32, target_h: u32) -> (u32, u32) {
    if source_w <= target_w && source_h <= target_h {
        return (source_w.max(1), source_h.max(1));
    }
    let scale = (target_w as f64 / source_w as f64)
        .min(target_h as f64 / source_h as f64)
        .min(1.0);
    (
        ((source_w as f64 * scale).round() as u32).max(1),
        ((source_h as f64 * scale).round() as u32).max(1),
    )
}

fn compute_cover(source_w: u32, source_h: u32, target_w: u32, target_h: u32) -> (u32, u32) {
    if source_w <= target_w && source_h <= target_h {
        return (source_w.max(1), source_h.max(1));
    }
    let scale = (target_w as f64 / source_w as f64)
        .max(target_h as f64 / source_h as f64)
        .min(1.0);
    (
        ((source_w as f64 * scale).round() as u32).max(1),
        ((source_h as f64 * scale).round() as u32).max(1),
    )
}

fn read_jpeg_exif_orientation(bytes: &[u8]) -> u8 {
    let mut i = 2usize;
    while i + 4 < bytes.len() {
        if bytes[i] != 0xFF {
            break;
        }
        let marker = bytes[i + 1];

        match marker {
            0xE1 => {
                let seg_len =
                    u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
                let exif_start = i + 4;
                if exif_start + 8 <= bytes.len()
                    && &bytes[exif_start..exif_start + 6] == b"Exif\0\0"
                {
                    return parse_exif_from_tiff(
                        &bytes[exif_start + 6..][..seg_len.saturating_sub(6)],
                    );
                }
                i += seg_len + 2;
            }
            0xD9 | 0xDA => {
                break;
            }
            0xD0..=0xD7 | 0x01 => {
                i += 2;
            }
            0x00 => {
                i += 1;
            }
            _ => {
                let seg_len =
                    u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
                i += seg_len + 2;
            }
        }
    }
    1
}

fn parse_exif_from_tiff(data: &[u8]) -> u8 {
    if data.len() < 8 {
        return 1;
    }

    let le = match &data[0..2] {
        b"II" => true,
        b"MM" => false,
        _ => return 1,
    };

    let ifd_off = if le {
        u32::from_le_bytes([data[4], data[5], data[6], data[7]]) as usize
    } else {
        u32::from_be_bytes([data[4], data[5], data[6], data[7]]) as usize
    };

    if ifd_off + 2 > data.len() {
        return 1;
    }

    let num = if le {
        u16::from_le_bytes([data[ifd_off], data[ifd_off + 1]])
    } else {
        u16::from_be_bytes([data[ifd_off], data[ifd_off + 1]])
    } as usize;

    for i in 0..num {
        let entry = ifd_off + 2 + i * 12;
        if entry + 12 > data.len() {
            break;
        }
        let tag = if le {
            u16::from_le_bytes([data[entry], data[entry + 1]])
        } else {
            u16::from_be_bytes([data[entry], data[entry + 1]])
        };
        if tag == 0x0112 {
            let val = if le {
                u16::from_le_bytes([data[entry + 8], data[entry + 9]])
            } else {
                u16::from_be_bytes([data[entry + 8], data[entry + 9]])
            };
            if (1..=8).contains(&val) {
                return val as u8;
            }
            return 1;
        }
    }
    1
}

fn apply_exif_orientation(img: &RgbaImage, orientation: u8) -> RgbaImage {
    match orientation {
        1 => img.clone(),
        2 => image::imageops::flip_horizontal(img),
        3 => image::imageops::rotate180(img),
        4 => image::imageops::flip_vertical(img),
        5 => {
            let flipped = image::imageops::flip_horizontal(img);
            image::imageops::rotate270(&flipped)
        }
        6 => image::imageops::rotate90(img),
        7 => {
            let flipped = image::imageops::flip_horizontal(img);
            image::imageops::rotate90(&flipped)
        }
        8 => image::imageops::rotate270(img),
        _ => img.clone(),
    }
}

fn decode_image(
    request_id: u64,
    path: PathBuf,
    target_w: u32,
    target_h: u32,
    cover: bool,
    viewer: bool,
) -> Option<Vec<u8>> {
    if is_stale(request_id, viewer) {
        return None;
    }

    let bytes = std::fs::read(&path).ok()?;
    if is_stale(request_id, viewer) {
        return None;
    }

    let orientation = read_jpeg_exif_orientation(&bytes);
    if is_stale(request_id, viewer) {
        return None;
    }

    let reader = ImageReader::new(Cursor::new(&bytes)).with_guessed_format().ok()?;
    let (raw_w, raw_h) = reader.into_dimensions().ok()?;
    if raw_w == 0 || raw_h == 0 {
        return None;
    }
    if is_stale(request_id, viewer) {
        return None;
    }

    let (eff_w, eff_h) = match orientation {
        5 | 6 | 7 | 8 => (raw_h, raw_w),
        _ => (raw_w, raw_h),
    };

    let (final_w, final_h) = if cover {
        compute_cover(eff_w, eff_h, target_w.max(1), target_h.max(1))
    } else {
        compute_fit(eff_w, eff_h, target_w.max(1), target_h.max(1))
    };
    if is_stale(request_id, viewer) {
        return None;
    }

    let (decode_w, decode_h) = match orientation {
        5 | 6 | 7 | 8 => (final_h, final_w),
        _ => (final_w, final_h),
    };

    let is_jpeg = bytes.len() >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF;
    let mut rgba = if is_jpeg {
        decode_jpeg_to_rgba(&bytes, decode_w, decode_h, request_id, viewer)
            .or_else(|| standard_decode_rgba(&bytes, decode_w, decode_h, request_id, viewer))
    } else {
        standard_decode_rgba(&bytes, decode_w, decode_h, request_id, viewer)
    }?;
    if is_stale(request_id, viewer) {
        return None;
    }

    if orientation != 1 {
        rgba = apply_exif_orientation(&rgba, orientation);
    }

    if rgba.width() != final_w || rgba.height() != final_h {
        rgba = image::imageops::resize(&rgba, final_w, final_h, FilterType::Triangle);
    }
    if is_stale(request_id, viewer) {
        return None;
    }

    let pixels = rgba.into_raw();
    let mut output = Vec::with_capacity(16 + pixels.len());
    output.extend_from_slice(&(final_w as i32).to_le_bytes());
    output.extend_from_slice(&(final_h as i32).to_le_bytes());
    output.extend_from_slice(&(eff_w as i32).to_le_bytes());
    output.extend_from_slice(&(eff_h as i32).to_le_bytes());
    output.extend_from_slice(&pixels);
    Some(output)
}

fn decode_jpeg_to_rgba(
    bytes: &[u8],
    decode_w: u32,
    decode_h: u32,
    request_id: u64,
    viewer: bool,
) -> Option<RgbaImage> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(bytes));
    let actual = decoder
        .scale(
            decode_w.min(u16::MAX as u32) as u16,
            decode_h.min(u16::MAX as u32) as u16,
        )
        .ok()?;
    let (jpeg_w, jpeg_h) = (actual.0 as u32, actual.1 as u32);
    if jpeg_w == 0 || jpeg_h == 0 {
        return None;
    }
    if is_stale(request_id, viewer) {
        return None;
    }
    let rgb = decoder.decode().ok()?;
    let mut rgba = RgbaImage::new(jpeg_w, jpeg_h);
    for (y, row) in rgb.chunks_exact(jpeg_w as usize * 3).enumerate() {
        for (x, pixel) in row.chunks_exact(3).enumerate() {
            rgba.put_pixel(x as u32, y as u32, image::Rgba([pixel[0], pixel[1], pixel[2], 255]));
        }
    }
    if jpeg_w == decode_w && jpeg_h == decode_h {
        Some(rgba)
    } else {
        Some(image::imageops::resize(&rgba, decode_w, decode_h, FilterType::Triangle))
    }
}

fn standard_decode_rgba(
    bytes: &[u8],
    decode_w: u32,
    decode_h: u32,
    request_id: u64,
    viewer: bool,
) -> Option<RgbaImage> {
    let mut reader = ImageReader::new(Cursor::new(bytes)).with_guessed_format().ok()?;
    {
        let mut limits = image::io::Limits::default();
        limits.max_alloc =
            Some((decode_w as u64 * decode_h as u64 * 4).max(256 * 1024 * 1024));
        reader.limits(limits);
    }
    let decoded = reader.decode().ok()?;
    if is_stale(request_id, viewer) {
        return None;
    }
    let rgba = if decoded.width() == decode_w && decoded.height() == decode_h {
        decoded.to_rgba8()
    } else {
        decoded
            .resize_exact(decode_w, decode_h, FilterType::Triangle)
            .to_rgba8()
    };
    if is_stale(request_id, viewer) {
        return None;
    }
    Some(rgba)
}

fn decode_to_jbyte_array(
    mut env: JNIEnv,
    request_id: jlong,
    path_jstr: JString,
    target_w: jint,
    target_h: jint,
    cover: bool,
    viewer: bool,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path_str: String = env.get_string(&path_jstr).ok()?.into();
        let request_id = request_id as u64;
        let decoded = decode_image(
            request_id,
            PathBuf::from(path_str),
            target_w.max(1) as u32,
            target_h.max(1) as u32,
            cover,
            viewer,
        )?;
        let array = env.byte_array_from_slice(&decoded).ok()?;
        Some(array.into_raw())
    }));

    match result {
        Ok(Some(array)) => array,
        Ok(None) => std::ptr::null_mut(),
        Err(_) => {
            lin_log!(
                crate::lin_logger::LEVEL_ERROR,
                "image_pipeline",
                "native decode panic recovered"
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeImagePipeline_nativeDecodeThumbnail(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
    path_jstr: JString,
    target_w: jint,
    target_h: jint,
) -> jbyteArray {
    decode_to_jbyte_array(env, request_id, path_jstr, target_w, target_h, true, false)
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeImagePipeline_nativeDecodeViewer(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
    path_jstr: JString,
    target_w: jint,
    target_h: jint,
) -> jbyteArray {
    decode_to_jbyte_array(env, request_id, path_jstr, target_w, target_h, false, true)
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeImagePipeline_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) {
    mark_cancelled(request_id as u64);
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeImagePipeline_nativeCancelAllExcept(
    _env: JNIEnv,
    _class: JClass,
    keep_request_id: jlong,
) {
    let keep = keep_request_id as u64;
    LATEST_VIEWER_REQUEST.store(keep, Ordering::Release);
    if let Ok(mut set) = cancelled_requests().lock() {
        set.clear();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeImagePipeline_nativeTrim(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut set) = cancelled_requests().lock() {
        set.clear();
    }
    crate::memory_manager::purge_jemalloc();
}
