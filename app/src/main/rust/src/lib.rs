mod device_watcher;
mod image_pipeline;
pub mod lin_logger;
mod mtp_bridge;
mod memory_manager;

#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use jwalk::WalkDirGeneric;
use std::fs;
use std::path::Path;

const SUPPORTED_EXTENSIONS: &[&str] = &["png", "jpg", "jpeg", "webp", "bmp", "tiff", "tif", "svg"];

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_NativeScanner_nativeScan(
    mut env: JNIEnv,
    _class: JClass,
    roots_jstr: JString,
) -> jstring {
    let roots_str: String = match env.get_string(&roots_jstr) {
        Ok(s) => s.into(),
        Err(_) => {
            return env
                .new_string("")
                .expect("Failed to create empty JNI string")
                .into_raw();
        }
    };

    let roots: Vec<&str> = roots_str.split(';').filter(|s| !s.is_empty()).collect();
    let mut results = String::with_capacity(1024 * 1024);

    for root_path in roots {
        let resolved_path = if root_path.starts_with('~') {
            if let Some(home) = std::env::var_os("HOME") {
                let home_path = Path::new(&home);
                home_path.join(root_path.trim_start_matches("~/"))
            } else {
                Path::new(root_path).to_path_buf()
            }
        } else {
            Path::new(root_path).to_path_buf()
        };

        if !resolved_path.exists() {
            continue;
        }

        let walker = WalkDirGeneric::<((), bool)>::new(&resolved_path)
            .skip_hidden(true)
            .process_read_dir(|_depth, _path, _read_dir_state, children| {
                children.retain(|dir_entry_result| {
                    if let Ok(dir_entry) = dir_entry_result {
                        let name = dir_entry.file_name.to_string_lossy();
                        let lower = name.to_ascii_lowercase();
                        if name == "lost+found" || name.starts_with('.')
                            || lower == "android" || lower == "music"
                            || lower == "movies" || lower == "documents"
                        {
                            return false;
                        }
                    }
                    true
                });
            });

        for entry_result in walker {
            let entry = match entry_result {
                Ok(e) => e,
                Err(_) => continue,
            };

            if !entry.file_type.is_file() {
                continue;
            }

            let path = entry.path();

            let ext = match path.extension() {
                Some(e) => e,
                None => continue,
            };

            let ext_str = ext.to_string_lossy().to_lowercase();
            if !SUPPORTED_EXTENSIONS.contains(&ext_str.as_str()) {
                continue;
            }

            let metadata = match fs::metadata(&path) {
                Ok(m) => m,
                Err(_) => continue,
            };

            let size = metadata.len();
            let modified_ms = metadata
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::SystemTime::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);

            let path_str = path.to_string_lossy();

            results.push_str(&path_str);
            results.push('\t');
            results.push_str(&size.to_string());
            results.push('\t');
            results.push_str(&modified_ms.to_string());
            results.push('\n');
        }
    }

    match env.new_string(results) {
        Ok(js) => js.into_raw(),
        Err(_) => env
            .new_string("")
            .expect("Failed to create empty JNI string")
            .into_raw(),
    }
}
