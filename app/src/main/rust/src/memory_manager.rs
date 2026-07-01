use std::fs;
use std::io::{BufRead, BufReader};

use jni::objects::JClass;
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use crate::lin_log;

pub fn get_memory_usage_json() -> String {
    let vm_rss_kb = read_proc_field("/proc/self/status", "VmRSS:").unwrap_or(0);
    let vm_peak_kb = read_proc_field("/proc/self/status", "VmPeak:").unwrap_or(0);
    let (mem_total_kb, mem_avail_kb) = read_meminfo().unwrap_or((0, 0));

    let mtp_stats = get_mtp_memory_stats();

    let _ = tikv_jemalloc_ctl::epoch::advance();
    let jemalloc_allocated = tikv_jemalloc_ctl::stats::allocated::read().unwrap_or(0);
    let jemalloc_active = tikv_jemalloc_ctl::stats::active::read().unwrap_or(0);
    let jemalloc_resident = tikv_jemalloc_ctl::stats::resident::read().unwrap_or(0);

    let json = serde_json::json!({
        "native_heap_mb": vm_rss_kb / 1024,
        "peak_heap_mb": vm_peak_kb / 1024,
        "system_total_mb": mem_total_kb / 1024,
        "system_avail_mb": mem_avail_kb / 1024,
        "mtp_index_tsv_bytes": mtp_stats.tsv_bytes,
        "mtp_mount_count": mtp_stats.mount_count,
        "jemalloc_allocated_mb": jemalloc_allocated / (1024 * 1024),
        "jemalloc_active_mb": jemalloc_active / (1024 * 1024),
        "jemalloc_resident_mb": jemalloc_resident / (1024 * 1024),
    });

    lin_log!(
        crate::lin_logger::LEVEL_TRACE,
        "memory_manager",
        "get_memory_usage_json: {}",
        json.to_string()
    );

    json.to_string()
}

pub fn trim_native_memory(level: u32) -> u64 {
    let mut freed: u64 = 0;
    let mut mounts = crate::mtp_bridge::MOUNTS.lock().unwrap();

    match level {
        0 | 1 => { }
        2 | 3 | _ => {
            for (_, entry) in mounts.iter_mut() {
                freed += entry.image_index_tsv.len() as u64;
                entry.image_index_tsv = String::new();
            }
            lin_log!(
                crate::lin_logger::LEVEL_INFO,
                "memory_manager",
                "Level {} trim: cleared index TSVs ({} bytes freed)",
                level, freed
            );
        }
    }

    purge_jemalloc();
    freed
}

const PURGE_ALL_ARENAS: &[u8] = b"arena.4096.purge\0";

pub fn purge_jemalloc() {
    use std::sync::atomic::{AtomicBool, Ordering};
    static WARNED: AtomicBool = AtomicBool::new(false);

    let ret = unsafe {
        tikv_jemalloc_sys::mallctl(
            PURGE_ALL_ARENAS.as_ptr() as *const std::os::raw::c_char,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            0,
        )
    };
    if ret != 0 && !WARNED.swap(true, Ordering::Relaxed) {
        lin_log!(
            crate::lin_logger::LEVEL_WARN,
            "memory_manager",
            "jemalloc_purge failed with code {} ({})",
            ret,
            std::io::Error::from_raw_os_error(ret),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_MemoryManager_nativeGetMemoryUsage(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = get_memory_usage_json();
    env.new_string(&json)
        .unwrap_or_else(|_| env.new_string("{}").expect("Failed to create fallback JNI string"))
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_MemoryManager_nativeTrimLevel(
    _env: JNIEnv,
    _class: JClass,
    level: jint,
) -> jlong {
    trim_native_memory(level as u32) as jlong
}

fn read_proc_field(path: &str, field: &str) -> Option<u64> {
    let file = match fs::File::open(path) {
        Ok(f) => f,
        Err(e) => {
            lin_log!(
                crate::lin_logger::LEVEL_WARN,
                "memory_manager",
                "read_proc_field: failed to open {}: {}",
                path,
                e
            );
            return None;
        }
    };
    let reader = BufReader::new(file);
    for line in reader.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        if line.starts_with(field) {
            for token in line.split_whitespace() {
                if let Ok(val) = token.parse::<u64>() {
                    return Some(val);
                }
            }
        }
    }
    None
}

fn read_meminfo() -> Option<(u64, u64)> {
    let file = match fs::File::open("/proc/meminfo") {
        Ok(f) => f,
        Err(e) => {
            lin_log!(
                crate::lin_logger::LEVEL_WARN,
                "memory_manager",
                "read_meminfo: failed to open /proc/meminfo: {}",
                e
            );
            return None;
        }
    };
    let reader = BufReader::new(file);
    let mut total_kb = 0u64;
    let mut avail_kb = 0u64;
    for line in reader.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        if line.starts_with("MemTotal:") {
            for token in line.split_whitespace() {
                if let Ok(val) = token.parse::<u64>() {
                    total_kb = val;
                    break;
                }
            }
        } else if line.starts_with("MemAvailable:") {
            for token in line.split_whitespace() {
                if let Ok(val) = token.parse::<u64>() {
                    avail_kb = val;
                    break;
                }
            }
        }
    }
    Some((total_kb, avail_kb))
}

struct MtpMemoryStats {
    tsv_bytes: u64,
    mount_count: u64,
}

fn get_mtp_memory_stats() -> MtpMemoryStats {
    let mounts = crate::mtp_bridge::MOUNTS.lock().unwrap();
    let tsv_bytes: usize = mounts
        .values()
        .map(|entry| entry.image_index_tsv.len())
        .sum();
    MtpMemoryStats {
        tsv_bytes: tsv_bytes as u64,
        mount_count: mounts.len() as u64,
    }
}
