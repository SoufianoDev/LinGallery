use jni::objects::JValue;
use jni::sys::jint;
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

pub const LEVEL_TRACE: jint = 0;
pub const LEVEL_DEBUG: jint = 1;
pub const LEVEL_INFO: jint = 2;
pub const LEVEL_WARN: jint = 3;
pub const LEVEL_ERROR: jint = 4;

static NATIVE_MIN_LEVEL: AtomicI32 = AtomicI32::new(LEVEL_INFO);
static KOTLIN_MIN_LEVEL: AtomicI32 = AtomicI32::new(LEVEL_DEBUG);
static BUFFERED_MODE: AtomicBool = AtomicBool::new(false);
static FILE_LOGGING: AtomicBool = AtomicBool::new(false);

pub fn set_native_min_level(level: jint) {
    NATIVE_MIN_LEVEL.store(level, Ordering::Relaxed);
}

pub fn set_kotlin_min_level(level: jint) {
    KOTLIN_MIN_LEVEL.store(level, Ordering::Relaxed);
}

pub fn set_buffered(b: bool) {
    BUFFERED_MODE.store(b, Ordering::Release);
}

pub fn set_file_logging(enabled: bool) {
    FILE_LOGGING.store(enabled, Ordering::Release);
}

pub fn format_duration(ms: u64) -> String {
    let days = ms / 86400000;
    let hours = (ms % 86400000) / 3600000;
    let minutes = (ms % 3600000) / 60000;
    let seconds = (ms % 60000) / 1000;
    let millis = ms % 1000;

    match (days, hours, minutes, seconds) {
        (0, 0, 0, 0) => format!("{}ms", millis),
        (0, 0, 0, s) if millis == 0 => format!("{}s", s),
        (0, 0, 0, s) => format!("{}s {}ms", s, millis),
        (0, 0, m, s) if millis == 0 => format!("{}m {}s", m, s),
        (0, 0, m, s) => format!("{}m {}s {}ms", m, s, millis),
        (0, h, m, s) => format!("{}h {}m {}s", h, m, s),
        (d, h, _, _) => format!("{}d {}h", d, h),
    }
}

fn level_str(level: jint) -> &'static str {
    match level {
        0 => "TRACE",
        1 => "DEBUG",
        2 => "INFO ",
        3 => "WARN ",
        4 => "ERROR",
        _ => "?????",
    }
}

/// Core log function: writes to stderr and forwards to Kotlin via JNI.
pub fn log(level: jint, tag: &str, msg: &str) {
    let prefix = level_str(level);

    let buffered = BUFFERED_MODE.load(Ordering::Acquire);
    let file_logging = FILE_LOGGING.load(Ordering::Acquire);

    if !buffered || tag == "mtp_bridge" {
        eprintln!("{prefix} [{tag}] {msg}");
    }

    // Forward to Kotlin via JNI
    let level_threshold = NATIVE_MIN_LEVEL.load(Ordering::Relaxed);
    if level < level_threshold && !buffered && !file_logging {
        return;
    }

    if let Some(vm) = crate::mtp_bridge::JAVA_VM
        .lock()
        .unwrap()
        .as_ref()
        .map(|a| a.clone())
    {
        if let Ok(mut env) = vm.attach_current_thread() {
            forward_to_kotlin(&mut env, level, tag, msg);
        }
    }
}

#[macro_export]
macro_rules! lin_log {
    ($level:expr, $tag:expr, $fmt:literal, $($arg:expr),* $(,)?) => {
        $crate::lin_logger::log($level, $tag, &format!($fmt, $($arg),*))
    };
    ($level:expr, $tag:expr, $msg:expr) => {
        $crate::lin_logger::log($level, $tag, $msg)
    };
}

fn forward_to_kotlin(env: &mut JNIEnv, level: jint, tag: &str, msg: &str) {
    let cls = match env.find_class("com/soufianodev/lingallery/native/LinLogger") {
        Ok(c) => c,
        Err(_) => return,
    };
    let jtag = match env.new_string(tag) {
        Ok(s) => s,
        Err(_) => return,
    };
    let jmsg = match env.new_string(msg) {
        Ok(s) => s,
        Err(_) => return,
    };
    let _ = env.call_static_method(
        cls,
        "nativeLog",
        "(ILjava/lang/String;Ljava/lang/String;)V",
        &[
            JValue::Int(level),
            JValue::Object(&jtag.into()),
            JValue::Object(&jmsg.into()),
        ],
    );
}
