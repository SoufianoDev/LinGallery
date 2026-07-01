use std::collections::HashMap;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;
use crate::lin_log;

use mtp_rs::mtp::MtpDevice;

use crate::mtp_bridge;
use crate::mtp_bridge::with_jni_env;

#[derive(Debug, Clone, PartialEq)]
struct DeviceIdentity {
    serial: String,
    location_id: u64,
    product_id: u16,
}

static PREVIOUS_DEVICES: Mutex<Option<HashMap<String, DeviceIdentity>>> = Mutex::new(None);

pub fn start_device_watcher() {
    thread::spawn(|| loop {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(poll_once));
        if let Err(e) = result {
            let msg = if let Some(s) = e.downcast_ref::<&str>() {
                s.to_string()
            } else if let Some(s) = e.downcast_ref::<String>() {
                s.clone()
            } else {
                "unknown panic".to_string()
            };
            lin_log!(crate::lin_logger::LEVEL_ERROR, "device_watcher",
                "Poll thread panicked: {} — restarting in 3s", msg);
            thread::sleep(Duration::from_secs(3));
        }
    });
}

fn poll_once() {
    thread::sleep(Duration::from_secs(3));

    let current_devices = match MtpDevice::list_devices() {
        Ok(devices) => devices,
        Err(_) => return,
    };

    let current: HashMap<String, DeviceIdentity> = current_devices
        .iter()
        .filter_map(|d| {
            d.serial_number.as_ref().map(|serial| {
                (serial.clone(), DeviceIdentity {
                    serial: serial.clone(),
                    location_id: d.location_id,
                    product_id: d.product_id,
                })
            })
        })
        .collect();

    let previous = {
        let mut guard = PREVIOUS_DEVICES.lock().unwrap();
        guard.take().unwrap_or_default()
    };

    // Detect removed devices
    for (serial, _prev) in &previous {
        if !current.contains_key(serial) {
            if let Some(vm) = mtp_bridge::JAVA_VM.lock().unwrap().as_ref() {
                let _ = with_jni_env(vm, |env| {
                    mtp_bridge::notify_device_disconnected(env, serial);
                    Ok(())
                });
            }
        }
    }

    // Detect new and re-enumerated devices
    for (serial, curr) in &current {
        let is_new = match previous.get(serial) {
            Some(prev) => prev.location_id != curr.location_id || prev.product_id != curr.product_id,
            None => true,
        };

        if !is_new {
            continue;
        }

        // If re-enumeration, emit disconnect first
        if previous.contains_key(serial) {
            if let Some(vm) = mtp_bridge::JAVA_VM.lock().unwrap().as_ref() {
                let _ = with_jni_env(vm, |env| {
                    mtp_bridge::notify_device_disconnected(env, serial);
                    Ok(())
                });
            }
        }

        // Check if already mounted (single lock scope)
        let (already_mounted, mount_stale) = {
            let mounts = mtp_bridge::MOUNTS.lock().unwrap();
            let already = mounts.contains_key(serial);
            let stale = already && mounts.get(serial)
                .map(|e| !e.mount_path.exists())
                .unwrap_or(false);
            (already, stale)
        };
        if already_mounted && !mount_stale {
            continue;
        }
        if mount_stale {
            lin_log!(crate::lin_logger::LEVEL_WARN, "device_watcher", "Mount for {} is stale (path gone) — reconnecting", serial);
            mtp_bridge::MOUNTS.lock().unwrap().remove(serial);
        }

        // Build device info JSON with precise detection timestamp
        let device_info: String = {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos();
            current_devices
                .iter()
                .find(|d| d.serial_number.as_deref() == Some(serial.as_str()))
                .map(|d| {
                    format!(
                        r#"{{"serial":"{}","manufacturer":"{}","model":"{}","vid":{},"pid":{},"location_id":{},"detected_at_nanos":{}}}"#,
                        d.serial_number.as_deref().unwrap_or(""),
                        d.manufacturer.as_deref().unwrap_or(""),
                        d.product.as_deref().unwrap_or(""),
                        d.vendor_id,
                        d.product_id,
                        d.location_id,
                        now,
                    )
                })
                .unwrap_or_else(|| format!(r#"{{"serial":"{}","detected_at_nanos":{}}}"#, serial, now))
        };

        if let Some(vm) = mtp_bridge::JAVA_VM.lock().unwrap().as_ref() {
            let _ = with_jni_env(vm, |env| {
                mtp_bridge::notify_device_detected(env, &device_info);
                Ok(())
            });
        }
    }

    // Store current snapshot
    let mut guard = PREVIOUS_DEVICES.lock().unwrap();
    *guard = Some(current);
}
