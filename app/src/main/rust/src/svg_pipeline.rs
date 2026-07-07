use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring, JNI_TRUE, JNI_FALSE};
use jni::JNIEnv;

use once_cell::sync::Lazy;

use crate::lin_log;

// ── Document Registry ────────────────────────────────────────────

static NEXT_DOC_ID: AtomicI64 = AtomicI64::new(1);
static DOCUMENTS: Lazy<Mutex<HashMap<i64, SvgDocument>>> = Lazy::new(|| Mutex::new(HashMap::new()));

struct SvgDocument {
    tree: usvg::Tree,
    /// Raw SVG source string. The authoritative document state.
    source: String,
    original_size: (f32, f32),
}

fn next_doc_id() -> i64 {
    NEXT_DOC_ID.fetch_add(1, Ordering::SeqCst)
}

fn parse_svg(source: &str) -> Option<(usvg::Tree, (f32, f32))> {
    let opt = usvg::Options::default();
    let tree = usvg::Tree::from_data(source.as_bytes(), &opt).ok()?;
    let svg_w = tree.size().width();
    let svg_h = tree.size().height();
    // usvg normally resolves size from viewBox when width/height are absent.
    // tree.size() already accounts for the viewBox, so this fallback is only
    // reached for truly degenerate SVGs (no size, no viewBox).
    let ow = if svg_w > 0.0 { svg_w } else { 300.0 };
    let oh = if svg_h > 0.0 { svg_h } else { 150.0 };
    Some((tree, (ow, oh)))
}

// ── Internal Helpers ─────────────────────────────────────────────

fn render_to_pixmap(doc: &SvgDocument, width: u32, height: u32) -> Option<Vec<u8>> {
    let svg_w = doc.original_size.0;
    let svg_h = doc.original_size.1;

    // Guard: cannot render if the SVG has no usable size.
    if svg_w <= 0.0 || svg_h <= 0.0 {
        return None;
    }

    // Compute a uniform fit-scale so the SVG fills (width, height) without distortion.
    // This mirrors the fitScale logic in ImageDisplayContent on the Kotlin side.
    let scale_x = width.max(1) as f32 / svg_w;
    let scale_y = height.max(1) as f32 / svg_h;
    let scale   = scale_x.min(scale_y);

    // Rendered pixel dimensions after scaling.
    let render_w = ((svg_w * scale).round() as u32).max(1);
    let render_h = ((svg_h * scale).round() as u32).max(1);

    let mut pixmap = tiny_skia::Pixmap::new(render_w, render_h)?;

    // Apply the scale transform so SVG content fills the pixmap correctly.
    let transform = resvg::tiny_skia::Transform::from_scale(scale, scale);
    resvg::render(&doc.tree, transform, &mut pixmap.as_mut());

    let pixels = pixmap.data();

    // Header layout (matches NativeImagePipeline.parseResult / HEADER_BYTES = 16):
    //   bytes  0-3  : decoded_width  (i32 LE) = actual pixel width of this bitmap
    //   bytes  4-7  : decoded_height (i32 LE) = actual pixel height of this bitmap
    //   bytes  8-11 : source_width   (f32 LE) = SVG intrinsic width  (used for aspect-ratio)
    //   bytes 12-15 : source_height  (f32 LE) = SVG intrinsic height (used for aspect-ratio)
    //   bytes 16+   : raw RGBA pixels
    let mut output = Vec::with_capacity(16 + pixels.len());
    output.extend_from_slice(&(render_w as i32).to_le_bytes());
    output.extend_from_slice(&(render_h as i32).to_le_bytes());
    output.extend_from_slice(&svg_w.to_le_bytes());
    output.extend_from_slice(&svg_h.to_le_bytes());
    output.extend_from_slice(pixels);
    Some(output)
}

fn replace_document(doc: &mut SvgDocument, new_source: String) -> bool {
    match parse_svg(&new_source) {
        Some((tree, size)) => {
            doc.tree = tree;
            doc.source = new_source;
            doc.original_size = size;
            true
        }
        None => false,
    }
}

fn apply_transform_to_source(source: &str, transform_str: &str) -> String {
    if let Some(idx) = source.find("<g id=\"_lingallery_transform\" transform=\"") {
        let val_start = idx + "<g id=\"_lingallery_transform\" transform=\"".len();
        if let Some(quote_end) = source[val_start..].find('"') {
            let existing = &source[val_start..val_start + quote_end];
            let new_transform = format!("{} {}", transform_str, existing);
            let mut result = source.to_string();
            result.replace_range(val_start..val_start + quote_end, &new_transform);
            return result;
        }
    }

    if let Some(svg_open_end) = find_svg_open_end(source) {
        if let Some(svg_close_start) = source.rfind("</svg>") {
            let after_open = svg_open_end + 1;
            if source[..after_open].ends_with("/>") {
                let result = format!(
                    "{}{}</svg>",
                    &source[..after_open - 2],
                    format!("><g id=\"_lingallery_transform\" transform=\"{}\"/>", transform_str)
                );
                return result;
            }
            let g_open = format!("<g id=\"_lingallery_transform\" transform=\"{}\">", transform_str);
            let g_close = "</g>";
            let mut result = String::with_capacity(source.len() + g_open.len() + g_close.len());
            result.push_str(&source[..after_open]);
            result.push_str(&g_open);
            result.push_str(&source[after_open..svg_close_start]);
            result.push_str(g_close);
            result.push_str(&source[svg_close_start..]);
            return result;
        }
    }
    source.to_string()
}

fn find_svg_open_end(source: &str) -> Option<usize> {
    let bytes = source.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    while i < len {
        if bytes[i] == b'<' {
            if i + 5 <= len && &bytes[i + 1..i + 5] == b"svg "
                || (i + 5 <= len && &bytes[i + 1..i + 5] == b"svg>")
                || (i + 6 <= len && &bytes[i + 1..i + 6] == b"svg\n")
                || (i + 6 <= len && &bytes[i + 1..i + 6] == b"svg\r")
                || (i + 6 <= len && &bytes[i + 1..i + 6] == b"svg\t")
            {
                let start = i;
                let mut depth = 0u32;
                let mut in_quote = false;
                let mut quote_char = 0u8;
                for j in start..len {
                    if in_quote {
                        if bytes[j] == quote_char {
                            in_quote = false;
                        }
                        continue;
                    }
                    match bytes[j] {
                        b'"' | b'\'' => {
                            in_quote = true;
                            quote_char = bytes[j];
                        }
                        b'<' => depth += 1,
                        b'>' => {
                            if depth == 1 {
                                return Some(j);
                            }
                            depth = depth.saturating_sub(1);
                        }
                        _ => {}
                    }
                }
                return None;
            }
            while i < len && bytes[i] != b'>' {
                i += 1;
            }
        }
        i += 1;
    }
    None
}

fn modify_viewbox_in_source(
    source: &str,
    vx: f32,
    vy: f32,
    vw: f32,
    vh: f32,
    pw: f32,
    ph: f32,
) -> String {
    let mut result = source.to_string();
    let new_vb = format!("viewBox=\"{:.1} {:.1} {:.1} {:.1}\"", vx, vy, vw, vh);

    if !has_viewbox_attr(&result) {
        if let Some(pos) = find_svg_tag_insert_pos(&result) {
            result.insert_str(pos, &format!(" {} ", new_vb));
        }
    } else {
        result = replace_svg_attr(&result, "viewBox", &new_vb);
        result = replace_viewbox_attr(&result, &new_vb);
    }

    let new_w = format!("width=\"{:.1}\"", pw);
    let new_h = format!("height=\"{:.1}\"", ph);
    result = replace_svg_attr(&result, "width", &new_w);
    result = replace_svg_attr(&result, "height", &new_h);

    result
}

fn has_viewbox_attr(source: &str) -> bool {
    let svg_tag_end = find_svg_open_end(source).unwrap_or(0);
    let tag_content = &source[..svg_tag_end + 1];
    tag_content.to_ascii_lowercase().contains("viewbox")
}

fn find_svg_tag_insert_pos(source: &str) -> Option<usize> {
    if let Some(svg_start) = source.find("<svg") {
        let after_name = svg_start + 4;
        Some(after_name)
    } else {
        None
    }
}

fn replace_svg_attr(source: &str, attr_name: &str, new_value: &str) -> String {
    let svg_tag_end = find_svg_open_end(source).unwrap_or(0);
    let tag_content = &source[..svg_tag_end + 1];

    let search_for = |name: &str| -> Option<(usize, usize)> {
        let tag_bytes = tag_content.as_bytes();
        let name_bytes = name.as_bytes();
        let tag_len = tag_bytes.len();
        let mut i = 0;
        while i < tag_len {
            if is_space_or_quote(tag_bytes[i])
                && i + name_bytes.len() < tag_len
                && tag_bytes[i + 1..i + 1 + name_bytes.len()].eq_ignore_ascii_case(name_bytes)
            {
                let val_start = i + 1 + name_bytes.len();
                if val_start < tag_len && tag_bytes[val_start] == b'=' {
                    let quote_start = val_start + 1;
                    if quote_start < tag_len
                        && (tag_bytes[quote_start] == b'"' || tag_bytes[quote_start] == b'\'')
                    {
                        let q = tag_bytes[quote_start];
                        let mut val_end = quote_start + 1;
                        while val_end < tag_len && tag_bytes[val_end] != q {
                            val_end += 1;
                        }
                        if val_end < tag_len {
                            return Some((i + 1, val_end + 1));
                        }
                    }
                }
            }
            i += 1;
        }
        None
    };

    match search_for(attr_name) {
        Some((start, end)) => {
            let mut result = String::with_capacity(source.len());
            result.push_str(&source[..start]);
            result.push_str(&format!("{}", new_value));
            result.push_str(&source[end..]);
            result
        }
        None => {
            let insert_pos = if let Some(pos) = tag_content.rfind('>') {
                if pos > 0 && tag_content.as_bytes().get(pos - 1) == Some(&b'/') {
                    pos - 1
                } else {
                    pos
                }
            } else {
                return source.to_string();
            };
            let mut result = String::with_capacity(source.len() + new_value.len() + 1);
            result.push_str(&source[..insert_pos]);
            result.push(' ');
            result.push_str(new_value);
            result.push_str(&source[insert_pos..]);
            result
        }
    }
}

fn is_space_or_quote(b: u8) -> bool {
    matches!(b, b' ' | b'\n' | b'\r' | b'\t')
}

fn replace_viewbox_attr(source: &str, new_vb: &str) -> String {
    let bytes = source.as_bytes();
    let vb_bytes = b"viewbox";
    let len = bytes.len();
    let mut i = 0;
    while i + vb_bytes.len() < len {
        if bytes[i..i + vb_bytes.len()].eq_ignore_ascii_case(vb_bytes) {
            if i == 0 || is_space_or_quote(bytes[i - 1]) {
                let after = i + vb_bytes.len();
                if after < len && bytes[after] == b'=' {
                    let quote_start = after + 1;
                    if quote_start < len
                        && (bytes[quote_start] == b'"' || bytes[quote_start] == b'\'')
                    {
                        let q = bytes[quote_start];
                        let mut val_end = quote_start + 1;
                        while val_end < len && bytes[val_end] != q {
                            val_end += 1;
                        }
                        if val_end < len {
                            val_end += 1;
                            let mut result = String::with_capacity(source.len());
                            result.push_str(&source[..i]);
                            result.push_str(new_vb);
                            result.push_str(&source[val_end..]);
                            return result;
                        }
                    }
                }
            }
        }
        i += 1;
    }
    source.to_string()
}

fn add_clip_path_to_source(source: &str, x: f32, y: f32, w: f32, h: f32) -> String {
    // Insert a <defs> with <clipPath> into the SVG, and wrap the root children in a group
    // with clip-path="url(#_lingallery_crop)"
    let clip_def = format!(
        "<defs><clipPath id=\"_lingallery_crop\"><rect x=\"{:.1}\" y=\"{:.1}\" width=\"{:.1}\" height=\"{:.1}\"/></clipPath></defs>",
        x, y, w, h
    );

    let g_clip = "<g clip-path=\"url(#_lingallery_crop)\">";
    let g_close = "</g>";

    // Find where to insert defs and the wrapping group
    if let Some(svg_end) = find_svg_open_end(source) {
        let after_open = svg_end + 1;
        let mut result = String::with_capacity(source.len() + clip_def.len() + g_clip.len() + g_close.len());
        result.push_str(&source[..after_open]);
        result.push_str(&clip_def);
        result.push_str(g_clip);
        result.push_str(&source[after_open..]);
        // Find the end of the content before </svg>
        if let Some(close_svg) = result.rfind("</svg>") {
            result.insert_str(close_svg, g_close);
        }
        result
    } else {
        source.to_string()
    }
}

fn parse_attrib_float(tag_content: &str, attr_name: &str) -> Option<f32> {
    let search = format!("{}=", attr_name);
    let idx = tag_content.find(&search)?;
    let val_start = idx + search.len();
    if val_start >= tag_content.len() { return None; }
    let quote_char = tag_content.as_bytes()[val_start];
    if quote_char != b'"' && quote_char != b'\'' { return None; }
    let val_content = &tag_content[val_start + 1..];
    let quote_end = val_content.find(quote_char as char)?;
    let val_str = &val_content[..quote_end];

    let end_num = val_str
        .find(|c: char| !c.is_numeric() && c != '.' && c != '-' && c != '+')
        .unwrap_or(val_str.len());
    val_str[..end_num].parse::<f32>().ok()
}

fn parse_attrib_viewbox(tag_content: &str) -> Option<(f32, f32, f32, f32)> {
    let idx = tag_content.find("viewBox=").or_else(|| tag_content.find("viewbox="))?;
    let val_start = idx + "viewBox=".len();
    if val_start >= tag_content.len() { return None; }
    let quote_char = tag_content.as_bytes()[val_start];
    if quote_char != b'"' && quote_char != b'\'' { return None; }
    let val_content = &tag_content[val_start + 1..];
    let quote_end = val_content.find(quote_char as char)?;
    let val_str = &val_content[..quote_end];

    let mut parts = val_str
        .split(|c| c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r')
        .filter(|s| !s.is_empty());

    let vx = parts.next()?.parse::<f32>().ok()?;
    let vy = parts.next()?.parse::<f32>().ok()?;
    let vw = parts.next()?.parse::<f32>().ok()?;
    let vh = parts.next()?.parse::<f32>().ok()?;
    Some((vx, vy, vw, vh))
}

fn parse_preserve_aspect_ratio(tag_content: &str) -> (bool, bool, f32, f32) {
    let default = (false, false, 0.5, 0.5); // (is_none, is_slice, align_x, align_y)
    let idx = tag_content.find("preserveAspectRatio=");
    let val = if let Some(i) = idx {
        let start = i + "preserveAspectRatio=".len();
        if start >= tag_content.len() { return default; }
        let q = tag_content.as_bytes()[start];
        if q != b'"' && q != b'\'' { return default; }
        let content = &tag_content[start + 1..];
        if let Some(end) = content.find(q as char) {
            &content[..end]
        } else {
            return default;
        }
    } else {
        return default;
    };

    let is_none = val.contains("none");
    if is_none {
        return (true, false, 0.5, 0.5);
    }
    let is_slice = val.contains("slice");
    let align_x = if val.contains("xMin") { 0.0 } else if val.contains("xMax") { 1.0 } else { 0.5 };
    let align_y = if val.contains("yMin") { 0.0 } else if val.contains("yMax") { 1.0 } else { 0.5 };

    (false, is_slice, align_x, align_y)
}

// ── JNI Exports ──────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgLoad(
    mut env: JNIEnv,
    _class: JClass,
    path_jstr: JString,
) -> jlong {
    let path_str: String = match env.get_string(&path_jstr) {
        Ok(s) => s.into(),
        Err(e) => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "svg_pipeline", "nativeSvgLoad: get_string failed: {}", e);
            return -1;
        }
    };

    let source = match std::fs::read_to_string(&path_str) {
        Ok(s) => s,
        Err(e) => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "svg_pipeline", "nativeSvgLoad: failed to read {}: {}", path_str, e);
            return -1;
        }
    };

    let (tree, size) = match parse_svg(&source) {
        Some(r) => r,
        None => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "svg_pipeline", "nativeSvgLoad: failed to parse SVG: {}", path_str);
            return -1;
        }
    };

    let doc = SvgDocument {
        tree,
        source,
        original_size: size,
    };

    let id = next_doc_id();
    if let Ok(mut docs) = DOCUMENTS.lock() {
        docs.insert(id, doc);
        lin_log!(crate::lin_logger::LEVEL_INFO, "svg_pipeline", "loaded SVG doc {} from {}", id, path_str);
        id as jlong
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgLoadFromString(
    mut env: JNIEnv,
    _class: JClass,
    content_jstr: JString,
) -> jlong {
    let content: String = match env.get_string(&content_jstr) {
        Ok(s) => s.into(),
        Err(e) => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "svg_pipeline", "nativeSvgLoadFromString: get_string failed: {}", e);
            return -1;
        }
    };

    let (tree, size) = match parse_svg(&content) {
        Some(r) => r,
        None => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "svg_pipeline", "nativeSvgLoadFromString: failed to parse SVG");
            return -1;
        }
    };

    let doc = SvgDocument {
        tree,
        source: content,
        original_size: size,
    };

    let id = next_doc_id();
    if let Ok(mut docs) = DOCUMENTS.lock() {
        docs.insert(id, doc);
        id as jlong
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgRender(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) -> jbyteArray {
    let docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    let doc = match docs.get(&handle) {
        Some(d) => d,
        None => {
            lin_log!(crate::lin_logger::LEVEL_WARN, "svg_pipeline", "nativeSvgRender: invalid handle {}", handle);
            return std::ptr::null_mut();
        }
    };

    match render_to_pixmap(doc, width.max(1) as u32, height.max(1) as u32) {
        Some(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgGetSize(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    let doc = match docs.get(&handle) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };

    let w = doc.original_size.0;
    let h = doc.original_size.1;
    let bytes = [w.to_le_bytes(), h.to_le_bytes()].concat();
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgSerialize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };

    let doc = match docs.get(&handle) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };

    match env.new_string(&doc.source) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgSaveToFile(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path_jstr: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&path_jstr) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let source = {
        let docs = match DOCUMENTS.lock() {
            Ok(d) => d,
            Err(_) => return JNI_FALSE,
        };
        let doc = match docs.get(&handle) {
            Some(d) => &d.source,
            None => return JNI_FALSE,
        };
        doc.clone()
    };

    match std::fs::write(&path_str, &source) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut docs) = DOCUMENTS.lock() {
        if docs.remove(&handle).is_some() {
            lin_log!(crate::lin_logger::LEVEL_INFO, "svg_pipeline", "released SVG doc {}", handle);
        }
    }
}

// ── Editing Operations ───────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgEditRotate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    degrees: jfloat,
) -> jboolean {
    let mut docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };
    let doc = match docs.get_mut(&handle) {
        Some(d) => d,
        None => return JNI_FALSE,
    };

    let tag_content = if let Some(end) = find_svg_open_end(&doc.source) {
        &doc.source[..end + 1]
    } else {
        ""
    };

    let (vx, vy, vw, vh) = parse_attrib_viewbox(tag_content)
        .unwrap_or((0.0, 0.0, doc.original_size.0, doc.original_size.1));

    let cx = vx + vw / 2.0;
    let cy = vy + vh / 2.0;
    let transform_str = format!("rotate({:.1} {:.1} {:.1})", degrees, cx, cy);
    let mut new_source = apply_transform_to_source(&doc.source, &transform_str);

    // If 90 or 270 degrees, swap width and height of viewBox and physical size
    if (degrees % 180.0).abs() > 0.1 {
        let new_vw = vh;
        let new_vh = vw;
        let new_vx = cx - new_vw / 2.0;
        let new_vy = cy - new_vh / 2.0;
        new_source = modify_viewbox_in_source(&new_source, new_vx, new_vy, new_vw, new_vh, doc.original_size.1, doc.original_size.0);
    }

    if replace_document(doc, new_source) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgEditFlipH(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let mut docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };
    let doc = match docs.get_mut(&handle) {
        Some(d) => d,
        None => return JNI_FALSE,
    };

    let tag_content = if let Some(end) = find_svg_open_end(&doc.source) {
        &doc.source[..end + 1]
    } else {
        ""
    };

    let (vx, _vy, vw, _vh) = parse_attrib_viewbox(tag_content)
        .unwrap_or((0.0, 0.0, doc.original_size.0, doc.original_size.1));

    let cx = vx + vw / 2.0;
    let transform_str = format!("translate({:.1},0) scale(-1,1)", cx * 2.0);
    let new_source = apply_transform_to_source(&doc.source, &transform_str);

    if replace_document(doc, new_source) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgEditFlipV(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let mut docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };
    let doc = match docs.get_mut(&handle) {
        Some(d) => d,
        None => return JNI_FALSE,
    };

    let tag_content = if let Some(end) = find_svg_open_end(&doc.source) {
        &doc.source[..end + 1]
    } else {
        ""
    };

    let (_vx, vy, _vw, vh) = parse_attrib_viewbox(tag_content)
        .unwrap_or((0.0, 0.0, doc.original_size.0, doc.original_size.1));

    let cy = vy + vh / 2.0;
    let transform_str = format!("translate(0,{:.1}) scale(1,-1)", cy * 2.0);
    let new_source = apply_transform_to_source(&doc.source, &transform_str);

    if replace_document(doc, new_source) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeSvgPipeline_nativeSvgEditCrop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    w: jfloat,
    h: jfloat,
) -> jboolean {
    let mut docs = match DOCUMENTS.lock() {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };
    let doc = match docs.get_mut(&handle) {
        Some(d) => d,
        None => return JNI_FALSE,
    };

    if w <= 0.0 || h <= 0.0 {
        return JNI_FALSE;
    }

    // Extract raw <svg> tag attributes to find viewBox and size
    let tag_content = if let Some(end) = find_svg_open_end(&doc.source) {
        &doc.source[..end + 1]
    } else {
        ""
    };

    let (vb_x, vb_y, vb_w, vb_h) = parse_attrib_viewbox(tag_content)
        .unwrap_or((0.0, 0.0, doc.original_size.0, doc.original_size.1));

    let svg_w = doc.original_size.0;
    let svg_h = doc.original_size.1;

    let (is_none, is_slice, align_x, align_y) = parse_preserve_aspect_ratio(tag_content);

    let (crop_vb_x, crop_vb_y, crop_vb_w, crop_vb_h) = if is_none || svg_w <= 0.0 || svg_h <= 0.0 || vb_w <= 0.0 || vb_h <= 0.0 {
        let scale_x = if svg_w > 0.0 { vb_w / svg_w } else { 1.0 };
        let scale_y = if svg_h > 0.0 { vb_h / svg_h } else { 1.0 };
        (vb_x + x * scale_x, vb_y + y * scale_y, w * scale_x, h * scale_y)
    } else {
        let scale_x = svg_w / vb_w;
        let scale_y = svg_h / vb_h;
        let scale = if is_slice { scale_x.max(scale_y) } else { scale_x.min(scale_y) };
        let render_w = vb_w * scale;
        let render_h = vb_h * scale;
        let offset_x = (svg_w - render_w) * align_x;
        let offset_y = (svg_h - render_h) * align_y;

        (
            vb_x + (x - offset_x) / scale,
            vb_y + (y - offset_y) / scale,
            w / scale,
            h / scale
        )
    };

    // Apply viewBox adjustment
    let new_source = modify_viewbox_in_source(
        &doc.source,
        crop_vb_x,
        crop_vb_y,
        crop_vb_w,
        crop_vb_h,
        w,
        h,
    );

    if replace_document(doc, new_source) {
        JNI_TRUE
    } else {
        // Fallback: use clip path
        let clip_source = add_clip_path_to_source(&doc.source, crop_vb_x, crop_vb_y, crop_vb_w, crop_vb_h);
        if replace_document(doc, clip_source) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }
}
