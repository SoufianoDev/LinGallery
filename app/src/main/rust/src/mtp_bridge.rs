use std::collections::{HashMap, HashSet, VecDeque};
use crate::lin_log;

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc, Once};
use std::time::{Duration, Instant};

use fuser::{
    AccessFlags, Config, Errno, FileAttr, FileHandle, FileType, Filesystem, FopenFlags, Generation,
    INodeNo, LockOwner, MountOption, OpenFlags, ReplyAttr, ReplyData, ReplyDirectory, ReplyEmpty,
    ReplyEntry, ReplyOpen, Request,
};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use mtp_rs::mtp::MtpDevice;
#[cfg(target_os = "linux")]
use nusb;
use mtp_rs::ptp::{DevicePropertyCode, ObjectFormatCode, PropertyDataType, PropertyValue};
use mtp_rs::{ObjectHandle, StorageId};
use mtp_rs::ptp::OperationCode;
use tokio::sync::Semaphore;
use mtp_rs::ptp::ObjectInfo;

pub const MTP_OK: jint = 0;
pub const MTP_ERR_PERMISSION: jint = 1;
pub const MTP_ERR_NO_STORAGE: jint = 2;
pub const MTP_ERR_MOUNT: jint = 3;
pub const MTP_ERR_ANDROID_NO_STORAGE: jint = 4;
pub const MTP_ERR_GENERIC: jint = -1;
pub const MTP_ERR_SAMSUNG_RESTRICTED: jint = 5;

const TTL: Duration = Duration::from_secs(5);
const ROOT_INO: u64 = 1;

fn mtp_io_sem() -> &'static Semaphore {
    use std::sync::OnceLock;
    static SEM: OnceLock<Semaphore> = OnceLock::new();
    SEM.get_or_init(|| Semaphore::new(4))
}

fn mtp_runtime() -> &'static tokio::runtime::Runtime {
    use std::sync::OnceLock;
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Runtime::new().expect("tokio runtime"))
}

#[derive(Clone)]
struct InodeEntry {
    parent_ino: u64,
    name: String,
    storage_id: StorageId,
    object_handle: Option<ObjectHandle>,
    is_dir: bool,
    size: u64,
    mtime: u64,
}

struct InodeManager {
    next_ino: u64,
    entries: HashMap<u64, InodeEntry>,
    children: HashMap<u64, Vec<(String, u64)>>,
}

impl InodeManager {
    fn new() -> Self {
        let mut entries = HashMap::new();
        let mut children = HashMap::new();
        entries.insert(
            ROOT_INO,
            InodeEntry {
                parent_ino: 0,
                name: String::new(),
                storage_id: StorageId::ALL,
                object_handle: None,
                is_dir: true,
                size: 0,
                mtime: 0,
            },
        );
        children.insert(ROOT_INO, Vec::new());
        Self { next_ino: ROOT_INO + 1, entries, children }
    }

    fn alloc(&mut self) -> u64 {
        let ino = self.next_ino;
        self.next_ino += 1;
        ino
    }

    fn get(&self, ino: u64) -> Option<InodeEntry> {
        self.entries.get(&ino).cloned()
    }

    fn add_storage(&mut self, name: &str, storage_id: StorageId) -> u64 {
        let ino = self.alloc();
        self.entries.insert(
            ino,
            InodeEntry {
                parent_ino: ROOT_INO,
                name: name.to_string(),
                storage_id,
                object_handle: None,
                is_dir: true,
                size: 0,
                mtime: 0,
            },
        );
        self.children.get_mut(&ROOT_INO).unwrap().push((name.to_string(), ino));
        self.children.insert(ino, Vec::new());
        ino
    }

    fn add_object(
        &mut self,
        parent_ino: u64,
        name: &str,
        storage_id: StorageId,
        object_handle: Option<ObjectHandle>,
        is_dir: bool,
        size: u64,
        mtime: u64,
    ) -> u64 {
        let ino = self.alloc();
        self.entries.insert(
            ino,
            InodeEntry {
                parent_ino,
                name: name.to_string(),
                storage_id,
                object_handle,
                is_dir,
                size,
                mtime,
            },
        );
        self.children.get_mut(&parent_ino).unwrap().push((name.to_string(), ino));
        if is_dir {
            self.children.insert(ino, Vec::new());
        }
        ino
    }

    fn children_of(&self, ino: u64) -> Vec<(String, u64)> {
        self.children.get(&ino).cloned().unwrap_or_default()
    }
}

#[derive(Debug, Clone)]
struct ValidationStats {
    total_raw: usize,
    duplicates_removed: usize,
    orphans_repaired: usize,
    synthetic_dirs_created: usize,
    zero_size_filtered: usize,
    impossible_dates_filtered: usize,
    restricted_mode: bool,
}

#[derive(Clone)]
struct ValidatedIndex {
    objects: Vec<ObjectInfo>,
    parent_cache: HashMap<u32, PathBuf>,
    #[allow(dead_code)]
    obj_map: HashMap<u32, ObjectInfo>,
    stats: ValidationStats,
}

#[derive(Clone, Debug)]
struct ImageRecord {
    handle: u32,
    storage_id: u32,
    parent_handle: u32,
    filename: String,
    size: u64,
    mtime: u64,
    virtual_path: PathBuf,
}

pub struct SharedFsState {
    pub inodes: Mutex<InodeManager>,
}

impl SharedFsState {
    pub fn add_records(&self, records: &[ImageRecord], mount_path: &Path) {
        let mut inodes = self.inodes.lock().unwrap();
        for rec in records {
            let relative = rec.virtual_path
                .strip_prefix(mount_path)
                .unwrap_or(&rec.virtual_path)
                .to_string_lossy()
                .to_string();
            let segments: Vec<&str> = relative.split('/')
                .filter(|s| !s.is_empty()).collect();
            let segs = if segments.len() > 1 { &segments[1..] } else { &[] };
            let mut cur = storage_ino(rec.storage_id, &inodes);
            for (i, seg) in segs.iter().enumerate() {
                let is_last = i == segs.len() - 1;
                if let Some((_, ino)) = inodes
                    .children_of(cur).iter()
                    .find(|(n, _)| n == seg)
                {
                    cur = *ino;
                } else if is_last {
                    inodes.add_object(
                        cur, seg, StorageId(rec.storage_id),
                        Some(ObjectHandle(rec.handle)), false, rec.size, rec.mtime,
                    );
                } else {
                    inodes.add_object(cur, seg, StorageId(rec.storage_id), None, true, 0, 0);
                    cur = inodes.children_of(cur).iter()
                        .find(|(n, _)| n == seg)
                        .map(|(_, ino)| *ino)
                        .unwrap_or(cur);
                }
            }
        }
    }
}

fn storage_ino(storage_id: u32, inodes: &InodeManager) -> u64 {
    for (ino, entry) in &inodes.entries {
        if entry.storage_id == StorageId(storage_id)
            && entry.is_dir
            && entry.parent_ino == ROOT_INO
        {
            return *ino;
        }
    }
    ROOT_INO
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum IndexState {
    Building,
    Ready,
    Failed,
}

impl IndexState {
    fn as_str(&self) -> &'static str {
        match self {
            IndexState::Building => "IndexBuilding",
            IndexState::Ready => "IndexReady",
            IndexState::Failed => "IndexFailed",
        }
    }
}

struct LazyGalleryFsInner {
    device: MtpDevice,
    mount_path: PathBuf,
    shared: Arc<SharedFsState>,
}

pub struct LazyGalleryFs {
    inner: Arc<LazyGalleryFsInner>,
}

impl LazyGalleryFs {
    fn new_empty(
        device: MtpDevice,
        mount_path: PathBuf,
        storage_info: &[(StorageId, String)],
    ) -> Self {
        let mut inodes = InodeManager::new();
        for (sid, desc) in storage_info {
            inodes.add_storage(desc, *sid);
        }
        Self {
            inner: Arc::new(LazyGalleryFsInner {
                device,
                mount_path: mount_path.clone(),
                shared: Arc::new(SharedFsState {
                    inodes: Mutex::new(inodes),
                }),
            }),
        }
    }

    fn file_attr(&self, entry: &InodeEntry, ino: u64) -> FileAttr {
        FileAttr {
            ino: INodeNo(ino),
            size: entry.size,
            blocks: if entry.size > 0 { entry.size / 512 + 1 } else { 0 },
            atime: std::time::UNIX_EPOCH,
            mtime: if entry.mtime > 0 {
                std::time::UNIX_EPOCH + Duration::from_millis(entry.mtime)
            } else {
                std::time::UNIX_EPOCH
            },
            ctime: std::time::UNIX_EPOCH,
            crtime: std::time::UNIX_EPOCH,
            kind: if entry.is_dir { FileType::Directory } else { FileType::RegularFile },
            perm: if entry.is_dir { 0o755 } else { 0o644 },
            nlink: 1,
            uid: 1000,
            gid: 1000,
            rdev: 0,
            blksize: 4096,
            flags: 0,
        }
    }
}

fn datetime_to_ms(dt: &mtp_rs::DateTime) -> u64 {
    let mut y = dt.year as u64;
    let m = dt.month as u64;
    let d = dt.day as u64;

    let y_adjusted = if m <= 2 { y -= 1; y } else { y };
    let era = y_adjusted / 400;
    let yoe = y_adjusted - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days_since_epoch = (era * 146097 + doe) as i64 - 719468i64;

    let secs = days_since_epoch * 86400
        + (dt.hour as i64) * 3600
        + (dt.minute as i64) * 60
        + (dt.second as i64);

    (secs * 1000) as u64
}

fn is_likely_hash(s: &str) -> bool {
    s.len() >= 32 && s.chars().all(|c| c.is_ascii_hexdigit())
}

fn image_extension(filename: &str) -> String {
    filename
        .rsplit_once('.')
        .map(|(_, ext)| format!(".{}", ext.to_ascii_lowercase()))
        .unwrap_or_default()
}

fn is_supported_image_object(obj: &ObjectInfo) -> bool {
    if obj.is_folder() {
        return false;
    }
    if obj.format.is_image() {
        return true;
    }
    matches!(
        image_extension(&obj.filename).as_str(),
        ".png" | ".jpg" | ".jpeg" | ".webp" | ".bmp" | ".tiff" | ".tif" | ".svg"
            | ".heic" | ".heif" | ".avif"
    )
}

const SKIP_FOLDERS: &[&str] = &["music", "documents", "movies"];

fn is_skipped_folder(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.starts_with('.') || SKIP_FOLDERS.contains(&lower.as_str())
}

fn parent_path_is_skipped(parent_handle: u32, parent_cache: &HashMap<u32, PathBuf>, storage_root: &Path) -> bool {
    let parent_path = match parent_cache.get(&parent_handle) {
        Some(p) => p,
        None => return false,
    };
    let relative = parent_path.strip_prefix(storage_root).unwrap_or(parent_path);
    let components: Vec<String> = relative.components()
        .filter_map(|c| match c {
            std::path::Component::Normal(name) => Some(name.to_string_lossy().to_lowercase()),
            _ => None,
        })
        .collect();

    for comp in &components {
        if is_skipped_folder(comp) {
            return true;
        }
    }

    if let Some(android_pos) = components.iter().position(|n| n == "android") {
        let suffix = &components[android_pos + 1..];
        if suffix.is_empty() || suffix[0] != "media" {
            return true;
        }
    }

    false
}

fn encode_image_records(records: &[ImageRecord]) -> String {
    let mut out = String::new();
    for rec in records {
        out.push_str(&rec.virtual_path.to_string_lossy());
        out.push('\t');
        out.push_str(&rec.size.to_string());
        out.push('\t');
        out.push_str(&rec.mtime.to_string());
        out.push('\t');
        out.push_str(&rec.filename);
        out.push('\t');
        out.push_str(&image_extension(&rec.filename));
        out.push('\n');
    }
    out
}

async fn get_device_friendly_name(device: &MtpDevice) -> Option<String> {
    match device.session()
        .get_device_prop_value_typed(
            DevicePropertyCode::Unknown(0xD402),
            PropertyDataType::String,
        )
        .await
    {
        Ok(PropertyValue::String(name)) if !name.is_empty() => Some(name),
        _ => None,
    }
}

async fn validate_and_repair_index(
    raw: Vec<ObjectInfo>,
    session: &mtp_rs::ptp::PtpSession,
    mount_path: &Path,
    _storage_name: &str,
) -> ValidatedIndex {
    let total_raw = raw.len();
    let mut stats = ValidationStats {
        total_raw,
        duplicates_removed: 0,
        orphans_repaired: 0,
        synthetic_dirs_created: 0,
        zero_size_filtered: 0,
        impossible_dates_filtered: 0,
        restricted_mode: false,
    };

    // Step 1: Dedup by handle
    let mut seen_handles = std::collections::HashSet::<u32>::new();
    let mut deduped: Vec<ObjectInfo> = Vec::with_capacity(raw.len());
    for obj in raw {
        if seen_handles.contains(&obj.handle.0) {
            stats.duplicates_removed += 1;
            continue;
        }
        seen_handles.insert(obj.handle.0);
        deduped.push(obj);
    }

    // Step 2: Build handle→object map
    let mut obj_map: HashMap<u32, ObjectInfo> = HashMap::with_capacity(deduped.len());
    for obj in &deduped {
        obj_map.insert(obj.handle.0, obj.clone());
    }

    // Step 3: Detect Samsung restricted mode
    let non_folders: Vec<&ObjectInfo> = deduped.iter().filter(|o| !o.is_folder()).collect();
    let total_non_folders = non_folders.len();
    if total_non_folders > 0 {
        let fused_count = non_folders
            .iter()
            .filter(|o| {
                let code: u16 = From::from(o.format);
                code == 0x3000 || code == 0x3001
            })
            .count();
        if fused_count as f64 / total_non_folders as f64 > 0.9 {
            stats.restricted_mode = true;
            lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                "Restricted mode: {}/{} non-folder objects are FUSE metadata (0x3000/0x3001)",
                fused_count, total_non_folders,
            );
        }
    }

    // Step 4: Filter zero-size and impossible dates
    let mut filtered: Vec<ObjectInfo> = Vec::with_capacity(deduped.len());
    for obj in deduped {
        if !obj.is_folder() && obj.size == 0 && !obj.format.is_image() {
            stats.zero_size_filtered += 1;
            continue;
        }
        if let Some(ref dt) = obj.modified {
            if dt.year < 1970 || dt.year > 2100 {
                stats.impossible_dates_filtered += 1;
                continue;
            }
        }
        filtered.push(obj);
    }

    // Step 5: Collect unique parent handles from non-folder objects
    let mut parent_needed: std::collections::HashSet<u32> = std::collections::HashSet::new();
    let mut path_map: HashMap<u32, PathBuf> = HashMap::new();
    let storage_root = mount_path.to_path_buf();

    for obj in &filtered {
        if !obj.is_folder() {
            let ph = obj.parent.0;
            if ph != 0 && ph != 0xFFFF_FFFF {
                parent_needed.insert(ph);
            }
        }
    }

    // Step 6: Pre-resolve parent paths
    let mut chain_cache: HashMap<u32, Vec<(u32, String)>> = HashMap::new();
    let mut orphans_to_fetch: Vec<u32> = Vec::new();

    'outer: for &ph in &parent_needed {
        let mut chain: Vec<(u32, String)> = Vec::new();
        let mut current = ph;
        loop {
            if current == 0 || current == 0xFFFF_FFFF {
                break;
            }
            if let Some(cached) = chain_cache.get(&current) {
                chain.extend(cached.iter().cloned());
                break;
            }
            match obj_map.get(&current) {
                Some(obj) => {
                    chain.push((current, obj.filename.clone()));
                    current = obj.parent.0;
                }
                None => {
                    orphans_to_fetch.push(ph);
                    continue 'outer;
                }
            }
        }
        chain.reverse();
        chain_cache.insert(ph, chain);
    }

    // Second pass: fetch orphan parents from device and create synthetic entries
    let mut orphan_synthetics: Vec<ObjectInfo> = Vec::new();
    for &ph in &orphans_to_fetch {
        let obj = match session.get_object_info_full(ObjectHandle(ph)).await {
            Ok(mut o) => {
                o.handle = ObjectHandle(ph);
                o
            }
            Err(_) => {
                stats.synthetic_dirs_created += 1;
                ObjectInfo {
                    handle: ObjectHandle(ph),
                    parent: ObjectHandle(0xFFFF_FFFF),
                    filename: format!("_orphan_{}", ph),
                    format: ObjectFormatCode::Association,
                    storage_id: StorageId(0),
                    ..Default::default()
                }
            }
        };
        let mut chain: Vec<(u32, String)> = Vec::new();
        let mut current = obj.parent.0;
        loop {
            if current == 0 || current == 0xFFFF_FFFF {
                break;
            }
            if let Some(cached) = chain_cache.get(&current) {
                chain.extend(cached.iter().cloned());
                break;
            }
            match obj_map.get(&current) {
                Some(parent_obj) => {
                    chain.push((current, parent_obj.filename.clone()));
                    current = parent_obj.parent.0;
                }
                None => {
                    stats.synthetic_dirs_created += 1;
                    orphan_synthetics.push(ObjectInfo {
                        handle: ObjectHandle(current),
                        parent: ObjectHandle(0xFFFF_FFFF),
                        filename: format!("_orphan_{}", current),
                        format: ObjectFormatCode::Association,
                        storage_id: StorageId(0),
                        ..Default::default()
                    });
                    chain.push((current, format!("_orphan_{}", current)));
                    break;
                }
            }
        }
        chain.reverse();
        chain.push((ph, obj.filename.clone()));
        chain_cache.insert(ph, chain);
        obj_map.insert(ph, obj);
    }

    for syn in &orphan_synthetics {
        let syn_ph = syn.handle.0;
        chain_cache.entry(syn_ph).or_insert_with(|| {
            vec![(syn_ph, syn.filename.clone())]
        });
        obj_map.insert(syn_ph, syn.clone());
        filtered.push(syn.clone());
    }

    for (&ph, chain) in &chain_cache {
        let mut path = storage_root.clone();
        for (_, name) in chain {
            path = path.join(name);
        }
        path_map.insert(ph, path);
    }

    stats.orphans_repaired = orphans_to_fetch.len();

    ValidatedIndex {
        objects: filtered,
        parent_cache: path_map,
        obj_map,
        stats,
    }
}

async fn build_fast_index<F>(
    device: &mtp_rs::MtpDevice,
    storage: &mtp_rs::Storage,
    storage_name: &str,
    mount_path: &Path,
    cancelled: &AtomicBool,
    on_batch: &F,
    on_progress: Option<&(dyn Fn(usize) + Sync)>,
    on_folder_batch: Option<&(dyn Fn(&[ImageRecord]) + Sync)>,
) -> Vec<ImageRecord>
where
    F: Fn(&[ImageRecord], usize),
{
    let all_objects = match pits_traverse(device, storage, mount_path, storage_name, cancelled, on_progress, on_folder_batch).await {
        Some(objs) => objs,
        None => return Vec::new(),
    };

    let validated = validate_and_repair_index(
        all_objects, device.session(), mount_path, storage_name,
    ).await;

    lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
        "Validation: {} raw, {} dup removed, {} orphans repaired, {} synth dirs, {} zero-size filtered, restricted={}",
        validated.stats.total_raw, validated.stats.duplicates_removed,
        validated.stats.orphans_repaired, validated.stats.synthetic_dirs_created,
        validated.stats.zero_size_filtered, validated.stats.restricted_mode,
    );

    if validated.objects.is_empty() {
        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "No objects after validation");
        return Vec::new();
    }

    let total = validated.objects.len();
    let mut rejected_logged = 0usize;
    let storage_root = mount_path.to_path_buf();

    let supported: Vec<&ObjectInfo> = validated.objects.iter()
        .filter(|obj| {
            if !is_supported_image_object(obj) {
                return false;
            }
            if parent_path_is_skipped(obj.parent.0, &validated.parent_cache, &storage_root) {
                if rejected_logged < 10 {
                    rejected_logged += 1;
                    lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
                        "REJECTED[{}]: handle={}, filename='{}' (in skipped folder)",
                        rejected_logged, obj.handle.0, obj.filename,
                    );
                }
                return false;
            }
            true
        })
        .collect();

    lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge",
        "build_fast_index: {} total objects, {} supported images",
        total, supported.len(),
    );

    if supported.is_empty() {
        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "No supported images found");
        return Vec::new();
    }

    let mut by_parent: HashMap<u32, Vec<&ObjectInfo>> = HashMap::new();
    for obj in &supported {
        by_parent.entry(obj.parent.0).or_default().push(obj);
    }

    let mut parent_handles: Vec<u32> = by_parent.keys().copied().collect();
    parent_handles.sort();

    let parent_cache = &validated.parent_cache;
    let mount_storage = mount_path.to_path_buf();

    let mut all_records: Vec<ImageRecord> = Vec::new();
    let mut cumulative = 0usize;

    for parent_handle in parent_handles {
        if cancelled.load(Ordering::Relaxed) { break; }

        let child_objects = match by_parent.get(&parent_handle) {
            Some(c) => c,
            None => continue,
        };

        let parent_path = parent_cache
            .get(&parent_handle)
            .cloned()
            .unwrap_or_else(|| mount_storage.clone());

        let mut batch: Vec<ImageRecord> = Vec::with_capacity(child_objects.len());
        for obj in child_objects {
            let mtime = obj.modified.as_ref()
                .map(datetime_to_ms)
                .unwrap_or(0);
            let mut virtual_path = parent_path.clone();
            virtual_path.push(&obj.filename);
            batch.push(ImageRecord {
                handle: obj.handle.0,
                storage_id: obj.storage_id.0,
                parent_handle,
                filename: obj.filename.clone(),
                size: obj.size,
                mtime,
                virtual_path,
            });
        }

        batch.sort_by(|a, b| b.mtime.cmp(&a.mtime).then(a.handle.cmp(&b.handle)));

        if cancelled.load(Ordering::Relaxed) { break; }

        cumulative += batch.len();
        on_batch(&batch, cumulative);
        all_records.extend(batch);
    }

    all_records.sort_by(|a, b| b.mtime.cmp(&a.mtime).then(a.handle.cmp(&b.handle)));
    all_records
}

/// A `WorkItem` represents a single directory to be explored by the PITS
/// traversal loop. Every discovered directory is converted into one WorkItem
/// and submitted to the work scheduler (a `VecDeque`), decoupling traversal
/// management from execution and eliminating recursive calls entirely.
struct WorkItem {
    handle: ObjectHandle,
    depth: u32,
    name: String,
    path: PathBuf,
    /// When `true`, only child folders named "media" are enqueued.
    /// Applied to the top-level "android/" directory so that only
    /// `android/media/…` is traversed and `android/data/`, `android/obb/`,
    /// etc. are excluded without inspecting their contents.
    restrict_children_to_media: bool,
}

/// Parallel Incremental Traversal Strategy (PITS)
///
/// Replaces recursive MTP scanning with an explicit BFS work queue.
/// Every discovered directory becomes an independent `WorkItem` submitted
/// to a `VecDeque` scheduler. No recursive calls are made at any depth.
///
/// Discovered image files are forwarded to `on_folder_batch` and
/// `on_progress` immediately as each directory is resolved, allowing the
/// UI and indexing pipeline to process results before the full traversal
/// completes (incremental processing).
///
/// Traversal order: Breadth-First Search — shallower folders are dequeued
/// first (`pop_front`), so top-level albums appear in the UI sooner.
async fn pits_traverse(
    device: &mtp_rs::MtpDevice,
    storage: &mtp_rs::Storage,
    mount_path: &Path,
    _storage_name: &str,
    cancelled: &AtomicBool,
    on_progress: Option<&(dyn Fn(usize) + Sync)>,
    on_folder_batch: Option<&(dyn Fn(&[ImageRecord]) + Sync)>,
) -> Option<Vec<ObjectInfo>> {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex};
    use tokio::sync::Notify;

    let session = device.session();
    let storage_id = storage.id();

    // ── Seed phase ────────────────────────────────────────────────────────
    lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
        "PITS seed: GetObjectHandles(storage={}, parent=ALL)...", storage_id.0);

    let _seed_permit = mtp_io_sem().acquire().await.ok();
    let root_objects: Vec<ObjectInfo> = match session
        .get_object_handles(storage_id, None, Some(mtp_rs::ObjectHandle::ALL))
        .await
    {
        Ok(handles) if !handles.is_empty() => {
            lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
                "  Seed: {} root handles, fetching info...", handles.len());
            let mut objects = Vec::with_capacity(handles.len());
            for handle in handles {
                if cancelled.load(Ordering::Relaxed) { break; }
                match session.get_object_info_full(handle).await {
                    Ok(mut info) => { info.handle = handle; objects.push(info); }
                    Err(e) => lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                        "  GetObjectInfo({}) failed: {}", handle.0, e),
                }
            }
            lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
                "  Seed collected {} root objects", objects.len());
            objects
        }
        Ok(_) => {
            lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "  Seed: 0 root handles");
            Vec::new()
        }
        Err(e) => {
            lin_log!(crate::lin_logger::LEVEL_ERROR, "mtp_bridge", "  Seed failed: {}", e);
            Vec::new()
        }
    };
    drop(_seed_permit);

    if root_objects.is_empty() {
        lin_log!(crate::lin_logger::LEVEL_ERROR, "mtp_bridge", "PITS: no objects at storage root");
        return None;
    }

    let seed_image_count = root_objects.iter()
        .filter(|o| is_supported_image_object(o))
        .count();

    // ── PITS Parallel BFS traversal ───────────────────────────────────────
    let all_objects = Arc::new(Mutex::new(root_objects.clone()));
    let visited: Arc<Mutex<HashSet<u32>>> = Arc::new(Mutex::new(
        root_objects.iter().map(|o| o.handle.0).collect()
    ));

    let mut initial_queue = VecDeque::new();
    for obj in &root_objects {
        if obj.is_folder() && !is_skipped_folder(&obj.filename) {
            let restrict = obj.filename.eq_ignore_ascii_case("android");
            initial_queue.push_back(WorkItem {
                handle: obj.handle,
                depth: 1,
                name: obj.filename.clone(),
                path: mount_path.join(&obj.filename),
                restrict_children_to_media: restrict,
            });
        }
    }

    const MAX_DEPTH: u32 = 10;
    lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
        "PITS: {} initial work items (BFS, max_depth={})", initial_queue.len(), MAX_DEPTH);

    let queue = Arc::new(Mutex::new(initial_queue));
    let notify = Arc::new(Notify::new());
    let active_workers = Arc::new(AtomicUsize::new(0));
    let cumulative_supported = Arc::new(AtomicUsize::new(seed_image_count));

    // Cap the worker pool to 2 to ensure at least 2 permits remain available
    // for FUSE read operations, preventing the "slow read" timeout.
    let num_workers = 2;
    let mut workers = Vec::new();

    for worker_id in 0..num_workers {
        let queue = Arc::clone(&queue);
        let all_objects = Arc::clone(&all_objects);
        let visited = Arc::clone(&visited);
        let notify = Arc::clone(&notify);
        let active_workers = Arc::clone(&active_workers);
        let cumulative_supported = Arc::clone(&cumulative_supported);

        // Copy references for the async block
        let session = session;
        let storage_id = storage_id;
        let mount_path = mount_path;
        let cancelled = cancelled;
        let on_progress = on_progress;
        let on_folder_batch = on_folder_batch;

        workers.push(async move {
            loop {
                if cancelled.load(Ordering::Relaxed) { break; }

                let notified = notify.notified();
                let item = {
                    let mut q = queue.lock().unwrap();
                    if let Some(i) = q.pop_front() {
                        Some(i)
                    } else {
                        if active_workers.load(Ordering::Relaxed) == 0 {
                            break;
                        }
                        None
                    }
                };

                if let Some(item) = item {
                    active_workers.fetch_add(1, Ordering::Relaxed);

                    if item.depth <= MAX_DEPTH {
                        lin_log!(crate::lin_logger::LEVEL_TRACE, "mtp_bridge",
                            "  PITS worker {} processing: '{}' depth={} handle={}", worker_id, item.name, item.depth, item.handle.0);

                        // Acquire semaphore permit BEFORE MTP calls to prevent FUSE starvation
                        let _permit = mtp_io_sem().acquire().await.ok();

                        if let Some(handles) = pits_retry_get_handles(session, storage_id, item.handle).await {
                            if !handles.is_empty() {
                                let mut children: Vec<ObjectInfo> = Vec::new();
                                let mut has_nomedia = false;
                                
                                for h in &handles {
                                    if cancelled.load(Ordering::Relaxed) { break; }
                                    let is_new = { visited.lock().unwrap().insert(h.0) };
                                    if !is_new { continue; }
                                    
                                    if let Ok(mut info) = session.get_object_info_full(*h).await {
                                        info.handle = *h;
                                        if info.filename == ".nomedia" { has_nomedia = true; }
                                        children.push(info);
                                    }
                                }

                                if !has_nomedia {
                                    let folder_image_count = children.iter()
                                        .filter(|info| is_supported_image_object(info))
                                        .count();

                                    if folder_image_count > 0 {
                                        lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
                                            "  '{}' → {} images (worker {})", item.name, folder_image_count, worker_id);

                                        if let Some(cb) = on_folder_batch {
                                            if !cancelled.load(Ordering::Relaxed) {
                                                let mut batch: Vec<ImageRecord> = Vec::with_capacity(folder_image_count);
                                                for obj in children.iter().filter(|info| is_supported_image_object(info)) {
                                                    let mtime = obj.modified.as_ref().map(datetime_to_ms).unwrap_or(0);
                                                    let mut virtual_path = item.path.clone();
                                                    virtual_path.push(&obj.filename);
                                                    batch.push(ImageRecord {
                                                        handle: obj.handle.0,
                                                        storage_id: obj.storage_id.0,
                                                        parent_handle: item.handle.0,
                                                        filename: obj.filename.clone(),
                                                        size: obj.size,
                                                        mtime,
                                                        virtual_path,
                                                    });
                                                }
                                                if !batch.is_empty() {
                                                    cb(&batch);
                                                }
                                            }
                                        }
                                    }

                                    let prev = cumulative_supported.fetch_add(folder_image_count, Ordering::Relaxed);
                                    if let Some(p) = on_progress {
                                        p(prev + folder_image_count);
                                    }

                                    let mut q = queue.lock().unwrap();
                                    for child in &children {
                                        let is_folder = child.is_folder();
                                        if is_folder && item.depth < MAX_DEPTH {
                                            let should_enqueue = if item.restrict_children_to_media {
                                                child.filename.eq_ignore_ascii_case("media")
                                            } else {
                                                !is_skipped_folder(&child.filename)
                                            };
                                            if should_enqueue {
                                                q.push_back(WorkItem {
                                                    handle: child.handle,
                                                    depth: item.depth + 1,
                                                    name: child.filename.clone(),
                                                    path: item.path.join(&child.filename),
                                                    restrict_children_to_media: false,
                                                });
                                            }
                                        }
                                    }
                                    drop(q);

                                    all_objects.lock().unwrap().extend(children);
                                }
                            }
                        }
                    }

                    active_workers.fetch_sub(1, Ordering::Relaxed);
                    notify.notify_waiters();
                } else {
                    notified.await;
                }
            }
        });
    }

    futures::future::join_all(workers).await;

    let all_objects_final = match Arc::try_unwrap(all_objects) {
        Ok(mutex) => mutex.into_inner().unwrap(),
        Err(arc) => arc.lock().unwrap().clone(),
    };
    
    let child_count = all_objects_final.len().saturating_sub(root_objects.len());
    let final_supported = cumulative_supported.load(Ordering::Relaxed);
    
    lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
        "PITS done: {} child objects ({} total, {} supported images)",
        child_count, all_objects_final.len(), final_supported);

    if all_objects_final.is_empty() {
        lin_log!(crate::lin_logger::LEVEL_ERROR, "mtp_bridge",
            "PITS: traversal yielded no objects");
        None
    } else {
        Some(all_objects_final)
    }
}

/// Attempts to list the children of `folder` with up to 3 retries.
/// Backoff grows linearly (10 ms, 20 ms, 30 ms) to give the device
/// time to recover from transient I/O errors.
async fn pits_retry_get_handles(
    session: &mtp_rs::ptp::PtpSession,
    storage_id: StorageId,
    folder: ObjectHandle,
) -> Option<Vec<ObjectHandle>> {
    for attempt in 0..3u64 {
        tokio::time::sleep(Duration::from_millis(10 * (attempt + 1))).await;
        match session.get_object_handles(storage_id, None, Some(folder)).await {
            Ok(handles) => return Some(handles),
            Err(_) if attempt < 2 => {
                lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge",
                    "  pits_retry_get_handles attempt {} failed, retrying...", attempt + 1);
            }
            Err(e) => {
                lin_log!(crate::lin_logger::LEVEL_ERROR, "mtp_bridge",
                    "  pits_retry_get_handles exhausted: {}", e);
                return None;
            }
        }
    }
    None
}

impl Filesystem for LazyGalleryFs {
    fn access(&self, _req: &Request, _ino: INodeNo, _mask: AccessFlags, reply: ReplyEmpty) {
        reply.ok();
    }

    fn lookup(&self, _req: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEntry) {
        let name_str = match name.to_str() {
            Some(s) => s.to_string(),
            None => return reply.error(Errno::ENOENT),
        };

        let parent = parent.0;
        let inodes = self.inner.shared.inodes.lock().unwrap();
        for (cname, cino) in inodes.children_of(parent) {
            if cname == name_str {
                if let Some(entry) = inodes.get(cino) {
                    let attr = self.file_attr(&entry, cino);
                    return reply.entry(&TTL, &attr, Generation(0));
                }
            }
        }
        reply.error(Errno::ENOENT)
    }

    fn getattr(&self, _req: &Request, ino: INodeNo, _fh: Option<FileHandle>, reply: ReplyAttr) {
        let inodes = self.inner.shared.inodes.lock().unwrap();
        match inodes.get(ino.0) {
            Some(entry) => {
                let attr = self.file_attr(&entry, ino.0);
                reply.attr(&TTL, &attr)
            }
            None => reply.error(Errno::ENOENT),
        }
    }

    fn readdir(
        &self,
        _req: &Request,
        ino: INodeNo,
        _fh: FileHandle,
        offset: u64,
        mut reply: ReplyDirectory,
    ) {
        let ino_u64 = ino.0;

        if offset == 0 {
            if reply.add(INodeNo(ino_u64), 1, FileType::Directory, ".") {
                reply.ok();
                return;
            }
        }
        if offset <= 1 && ino_u64 != ROOT_INO {
            let parent_ino = {
                let inodes = self.inner.shared.inodes.lock().unwrap();
                inodes.get(ino_u64).map(|e| e.parent_ino).unwrap_or(ROOT_INO)
            };
            if reply.add(INodeNo(parent_ino), 2, FileType::Directory, "..") {
                reply.ok();
                return;
            }
        }

        let inodes = self.inner.shared.inodes.lock().unwrap();
        let children = inodes.children_of(ino_u64);
        let mut idx = 3u64;
        for (cname, cino) in &children {
            if idx <= offset {
                idx += 1;
                continue;
            }
            if let Some(entry) = inodes.get(*cino) {
                let kind = if entry.is_dir { FileType::Directory } else { FileType::RegularFile };
                if reply.add(INodeNo(*cino), idx, kind, cname) {
                    break;
                }
                idx += 1;
            }
        }
        reply.ok()
    }

    fn open(&self, _req: &Request, ino: INodeNo, _flags: OpenFlags, reply: ReplyOpen) {
        let inodes = self.inner.shared.inodes.lock().unwrap();
        match inodes.get(ino.0) {
            Some(entry) if !entry.is_dir => {
                reply.opened(FileHandle(ino.0), FopenFlags::empty())
            }
            _ => reply.error(Errno::EISDIR),
        }
    }

    fn read(
        &self,
        _req: &Request,
        ino: INodeNo,
        _fh: FileHandle,
        offset: u64,
        size: u32,
        _flags: OpenFlags,
        _lock_owner: Option<LockOwner>,
        reply: ReplyData,
    ) {
        let entry = {
            let inodes = self.inner.shared.inodes.lock().unwrap();
            inodes.get(ino.0)
        };

        let Some(entry) = entry else {
            return reply.error(Errno::ENOENT);
        };

        if entry.is_dir {
            return reply.error(Errno::EISDIR);
        }

        let Some(handle) = entry.object_handle else {
            return reply.error(Errno::EIO);
        };

        if offset >= entry.size {
            return reply.data(&[]);
        }

        let end = (offset + size as u64).min(entry.size);
        let actual_size = (end - offset) as u32;
        let device = self.inner.device.clone();
        let storage_id = entry.storage_id;

        mtp_runtime().spawn(async move {
            let _permit = match tokio::time::timeout(
                Duration::from_secs(30),
                mtp_io_sem().acquire(),
            )
            .await
            {
                Ok(Ok(permit)) => permit,
                Ok(Err(_)) => return reply.error(Errno::EIO),
                Err(_) => return reply.error(Errno::ETIMEDOUT),
            };

            let start = Instant::now();

            let result = tokio::time::timeout(Duration::from_secs(30), async {
                let storage = device.storage(storage_id).await.ok()?;
                storage
                    .download_partial_64(handle, offset, actual_size)
                    .await
                    .ok()
            })
            .await;

            match result {
                Ok(Some(data)) => {
                    let elapsed = start.elapsed();
                    if elapsed > Duration::from_secs(5) {
                        lin_log!(
                            crate::lin_logger::LEVEL_WARN,
                            "mtp_bridge",
                            "slow read: {}",
                            crate::lin_logger::format_duration(elapsed.as_millis() as u64)
                        );
                    }
                    reply.data(&data);
                }
                _ => reply.error(Errno::EIO),
            }
        });
    }

    fn release(
        &self,
        _req: &Request,
        _ino: INodeNo,
        _fh: FileHandle,
        _flags: OpenFlags,
        _lock_owner: Option<LockOwner>,
        _flush: bool,
        reply: ReplyEmpty,
    ) {
        reply.ok()
    }
}

pub struct MountEntry {
    pub _session: Option<fuser::BackgroundSession>,
    pub mount_path: PathBuf,
    pub device_name: String,
    pub image_index_tsv: String,
    pub image_count: usize,
    pub index_state: IndexState,
}

pub static JAVA_VM: std::sync::LazyLock<Mutex<Option<Arc<jni::JavaVM>>>> =
    std::sync::LazyLock::new(|| Mutex::new(None));

pub static MOUNTS: std::sync::LazyLock<Mutex<HashMap<String, MountEntry>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));

static CANCEL_FLAGS: std::sync::LazyLock<Mutex<HashMap<String, Arc<AtomicBool>>>> =
    std::sync::LazyLock::new(|| Mutex::new(HashMap::new()));

static TRACING_INIT: Once = Once::new();

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeInit(
    env: JNIEnv,
    _class: JClass,
) {
    TRACING_INIT.call_once(|| {
        tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn"))
            )
            .init();
    });

    if let Ok(vm) = env.get_java_vm() {
        let mut stored = JAVA_VM.lock().unwrap();
        if stored.is_none() {
            *stored = Some(Arc::new(vm));
        }
    }
    crate::device_watcher::start_device_watcher();
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeMtpSetBufferedMode(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    crate::lin_logger::set_buffered(enabled != JNI_FALSE);
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_LinLogger_nativeSetFileLogging(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    crate::lin_logger::set_file_logging(enabled != JNI_FALSE);
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeListDevices(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mtp_runtime().block_on(async {
        let devices = match MtpDevice::list_devices() {
            Ok(d) => d,
            Err(_) => return String::from("[]"),
        };
        let parts: Vec<String> = devices
            .iter()
            .map(|d| {
                format!(
                    r#"{{"serial":"{}","manufacturer":"{}","model":"{}","vid":{},"pid":{},"location_id":{}}}"#,
                    d.serial_number.as_deref().unwrap_or(""),
                    d.manufacturer.as_deref().unwrap_or(""),
                    d.product.as_deref().unwrap_or(""),
                    d.vendor_id,
                    d.product_id,
                    d.location_id,
                )
            })
            .collect();
        format!("[{}]", parts.join(","))
    });

    env.new_string(&json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeListIndexedImages(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jstring {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap_or_default().into_raw(),
    };

    let index = {
        let mounts = MOUNTS.lock().unwrap();
        mounts
            .get(&serial)
            .map(|entry| entry.image_index_tsv.clone())
            .unwrap_or_default()
    };

    env.new_string(index).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeGetIndexMetrics(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jstring {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap_or_default().into_raw(),
    };

    let json = {
        let mounts = MOUNTS.lock().unwrap();
        match mounts.get(&serial) {
            Some(e) => {
                serde_json::json!({
                    "indexed_images": e.image_count,
                    "index_state": e.index_state.as_str(),
                })
                .to_string()
            }
            None => String::new(),
        }
    };

    env.new_string(&json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeGetMountInfo(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jstring {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("{}").unwrap().into_raw(),
    };

    let json = {
        let mounts = MOUNTS.lock().unwrap();
        match mounts.get(&serial) {
            Some(entry) => serde_json::json!({
                "device_name": entry.device_name,
                "state": entry.index_state.as_str(),
                "image_count": entry.image_count,
            }).to_string(),
            None => "{}".to_string(),
        }
    };

    env.new_string(&json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeProbeDevice(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jint {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return MTP_ERR_GENERIC,
    };

    mtp_runtime().block_on(async {
        let device = {
            let mut last_err;
            let mut attempt = 0u32;
            let device = loop {
                attempt += 1;
                let result = if attempt == 1 {
                    MtpDevice::builder()
                        .timeout(Duration::from_secs(15))
                        .open_by_serial(&serial)
                        .await
                } else {
                    #[cfg(target_os = "linux")]
                    {
                        if let Ok(devices) = nusb::list_devices().await {
                            for info in devices {
                                if info.serial_number().map(|s| s == serial.as_str()).unwrap_or(false) {
                                    if let Ok(d) = info.open().await {
                                        for iface in 0u8..16u8 {
                                            if d.detach_kernel_driver(iface).is_ok() {
                                                lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "Probe: detached kernel driver from interface {}", iface);
                                            }
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        tokio::time::sleep(Duration::from_millis(500)).await;
                    }
                    MtpDevice::builder()
                        .timeout(Duration::from_secs(15))
                        .open_by_serial(&serial)
                        .await
                };
                match result {
                    Ok(d) => break d,
                    Err(e) => {
                        last_err = e.to_string();
                        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                            "Probe: open device ({}) attempt {}/3: {}", serial, attempt, last_err);
                        if attempt >= 3 {
                            return MTP_ERR_PERMISSION;
                        }
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                }
            };
            lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Probe: device opened for {}", serial);
            device
        };

        let di = device.device_info();
        let supports_object_proplist = di.operations_supported.contains(&OperationCode::GetObjectPropList);
        let is_android = di.vendor_extension_desc.to_lowercase().contains("android.com")
            || supports_object_proplist;

        let mut last_attempt = 0u32;
        let max_attempts = 5;
        loop {
            last_attempt += 1;
            match device.storages().await {
                Ok(s) if !s.is_empty() => {
                    lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge",
                        "Probe: {} storage(s) found for {}", s.len(), serial);
                    return MTP_OK;
                }
                Ok(_) => {
                    lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                        "Probe: storages attempt {}/{} returned 0", last_attempt, max_attempts);
                    if last_attempt >= max_attempts {
                        return if is_android { MTP_ERR_ANDROID_NO_STORAGE } else { MTP_ERR_NO_STORAGE };
                    }
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
                Err(e) => {
                    lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                        "Probe: storages attempt {}/{} failed: {}", last_attempt, max_attempts, e);
                    if last_attempt >= max_attempts {
                        return MTP_ERR_PERMISSION;
                    }
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeMountDevice(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
    mount_path_jstr: JString,
) -> jint {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return MTP_ERR_GENERIC,
    };
    let mount_path_str: String = match env.get_string(&mount_path_jstr) {
        Ok(s) => s.into(),
        Err(_) => return MTP_ERR_GENERIC,
    };

    let mount_path = PathBuf::from(&mount_path_str);

    mtp_runtime().block_on(async {
        let device = {
            let mut last_err;
            let mut attempt = 0u32;
            let device = loop {
                attempt += 1;

                let result = if attempt == 1 {
                    MtpDevice::builder()
                        .timeout(Duration::from_secs(15))
                        .open_by_serial(&serial)
                        .await
                } else {
                    #[cfg(target_os = "linux")]
                    {
                        if let Ok(devices) = nusb::list_devices().await {
                            for info in devices {
                                if info.serial_number().map(|s| s == serial.as_str()).unwrap_or(false) {
                                    if let Ok(device) = info.open().await {
                                        for iface in 0u8..16u8 {
                                                if device.detach_kernel_driver(iface).is_ok() {
                                                lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "Detached kernel driver from interface {}", iface);
                                            }
                                        }
                                        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Kernel drivers detached for {}", serial);
                                    }
                                    break;
                                }
                            }
                        }
                        tokio::time::sleep(Duration::from_millis(500)).await;
                    }

                    MtpDevice::builder()
                        .timeout(Duration::from_secs(15))
                        .open_by_serial(&serial)
                        .await
                };

                match result {
                    Ok(d) => break d,
                    Err(e) => {
                        last_err = e.to_string();
                        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "open device ({}) attempt {}/3: {}", serial, attempt, last_err);
                        if attempt >= 3 {
                            return MTP_ERR_PERMISSION;
                        }
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                }
            };
            lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Device opened, session established");
            device
        };

        let di = device.device_info();
        let supports_object_proplist = di.operations_supported.contains(&OperationCode::GetObjectPropList);
        let is_android = di.vendor_extension_desc.to_lowercase().contains("android.com")
            || supports_object_proplist;
        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge",
            "Device info: manufacturer={}, model={}, vendor_extension_desc={}, is_android={}, supports_GetObjectPropList={}",
            di.manufacturer, di.model, di.vendor_extension_desc, is_android, supports_object_proplist,
        );

        let device_name = get_device_friendly_name(&device)
            .await
            .filter(|s| !s.is_empty() && !s.starts_with('.') && !is_likely_hash(s))
            .or_else(|| {
                let m = di.model.trim().to_string();
                if !m.is_empty() { Some(m) } else { None }
            })
            .unwrap_or_else(|| {
                let combined = format!("{} {}", di.manufacturer, di.model);
                let t = combined.trim().to_string();
                if !t.is_empty() { t } else { serial.clone() }
            });

        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Resolved device name: {}", device_name);

        lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "Fetching storage IDs...");
        let storages = {
            let mut attempt = 0u32;
            let max_attempts = 5;
            loop {
                attempt += 1;
                match device.storages().await {
                    Ok(s) if !s.is_empty() => {
                        lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "Got {} storage(s) on attempt {}", s.len(), attempt);
                        break s;
                    }
                    Ok(_) => {
                        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "storages() attempt {}/{} returned 0 storages", attempt, max_attempts);
                        if attempt >= max_attempts {
                            if is_android {
                                return MTP_ERR_ANDROID_NO_STORAGE;
                            }
                            return MTP_ERR_NO_STORAGE;
                        }
                        lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "  retrying in 2s...");
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                    Err(e) => {
                        lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "storages() attempt {}/{} failed: {}", attempt, max_attempts, e);
                        if attempt >= max_attempts {
                            return MTP_ERR_PERMISSION;
                        }
                        tokio::time::sleep(Duration::from_secs(2)).await;
                    }
                }
            }
        };

        let storage_info: Vec<(StorageId, String)> = storages
            .iter()
            .map(|s| (s.id(), s.info().description.clone()))
            .collect();

        lin_log!(crate::lin_logger::LEVEL_DEBUG, "mtp_bridge", "Storages: {:?}", storage_info);

        let bg_storages = storages;
        let storage_info_for_bg = storage_info.clone();

        let device_for_indexing = device.clone();

        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Mounting empty FUSE at {:?}...", mount_path);
        let fs = LazyGalleryFs::new_empty(
            device,
            mount_path.clone(),
            &storage_info,
        );
        let shared = Arc::clone(&fs.inner.shared);

        // Clean any stale kernel state at the mount path (leftover from a previous crash)
        let _ = std::process::Command::new("fusermount")
            .arg("-uz")
            .arg(&mount_path)
            .output();

        let mut config = Config::default();
        config.mount_options = vec![MountOption::RO, MountOption::NoExec];
        config.n_threads = Some(4);
        config.clone_fd = false;

        let bg = match fuser::Session::new(fs, &mount_path, &config)
            .and_then(|s| s.spawn())
        {
            Ok(bg) => bg,
            Err(e) => {
                lin_log!(crate::lin_logger::LEVEL_ERROR, "mtp_bridge", "FUSE mount failed for {}: {}", serial, e);
                return MTP_ERR_MOUNT;
            }
        };

        MOUNTS.lock().unwrap().insert(serial.clone(), MountEntry {
            _session: Some(bg),
            mount_path: mount_path.clone(),
            device_name: device_name.clone(),
            image_index_tsv: String::new(),
            image_count: 0,
            index_state: IndexState::Building,
        });

        let (fuse_tx, fuse_rx) = mpsc::sync_channel::<Vec<ImageRecord>>(32);
        let mp = mount_path.clone();
        let cancelled = Arc::new(AtomicBool::new(false));
        let cc = Arc::clone(&cancelled);

        CANCEL_FLAGS.lock().unwrap().insert(serial.clone(), Arc::clone(&cancelled));

        std::thread::spawn(move || {
            for batch in fuse_rx.iter() {
                if cc.load(Ordering::Relaxed) { break; }
                shared.add_records(&batch, &mp);
            }
        });

        let vm = JAVA_VM.lock().unwrap().clone();
        let serial_bg = serial.clone();
        let device_name_bg = device_name.clone();
        let mount_path_bg = mount_path.clone();

        std::thread::spawn(move || {
            indexing_thread(
                device_for_indexing,
                bg_storages,
                storage_info_for_bg,
                mount_path_bg,
                serial_bg,
                device_name_bg,
                fuse_tx,
                cancelled,
                vm,
            );
        });

        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Fast mount complete for {} - indexing in background", serial);
        MTP_OK
    })
}

fn indexing_thread(
    device: mtp_rs::MtpDevice,
    storages: Vec<mtp_rs::Storage>,
    storage_info: Vec<(StorageId, String)>,
    mount_path: PathBuf,
    serial: String,
    _device_name: String,
    fuse_tx: mpsc::SyncSender<Vec<ImageRecord>>,
    cancelled: Arc<AtomicBool>,
    vm: Option<Arc<jni::JavaVM>>,
) {
    mtp_runtime().block_on(async {
        let mut all_records: Vec<ImageRecord> = Vec::new();
        let start = Instant::now();
        let trial2_cumulative = std::sync::atomic::AtomicUsize::new(0);

        for (storage, (sid, sname)) in storages.iter().zip(storage_info.iter()) {
            if cancelled.load(Ordering::Relaxed) { break; }

            let smp = mount_path.join(&sname);
            lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Indexing storage {} ({})...", sid.0, sname);

            let on_progress = |count: usize| {
                if let Some(ref vm) = vm {
                    let _ = with_jni_env(vm, |env| {
                        notify_indexing_progress(env, &serial, count);
                        Ok(())
                    });
                }
            };

            let on_folder_batch = &|batch: &[ImageRecord]| {
                if cancelled.load(Ordering::Relaxed) { return; }
                let prev = trial2_cumulative.fetch_add(batch.len(), Ordering::Relaxed);
                let cumulative = prev + batch.len();
                let _ = fuse_tx.send(batch.to_vec());
                if let Some(ref vm) = vm {
                    let batch_tsv = encode_image_records(batch);
                    let _ = with_jni_env(vm, |env| {
                        notify_indexing_batch(env, &serial, cumulative, &batch_tsv);
                        Ok(())
                    });
                }
            };

            let records = build_fast_index(
                &device, storage, &sname, &smp, &cancelled,
                &|batch, count| {
                    if cancelled.load(Ordering::Relaxed) { return; }
                    let _ = fuse_tx.send(batch.to_vec());
                    if let Some(ref vm) = vm {
                        let batch_tsv = encode_image_records(batch);
                        let _ = with_jni_env(vm, |env| {
                            notify_indexing_batch(env, &serial, count, &batch_tsv);
                            Ok(())
                        });
                    }
                },
                Some(&on_progress),
                Some(on_folder_batch),
            ).await;

            all_records.extend(records);
        }

        if cancelled.load(Ordering::Relaxed) {
            lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge", "Indexing cancelled for {}", serial);
            return;
        }

        all_records.sort_by(|a, b| b.mtime.cmp(&a.mtime).then(a.handle.cmp(&b.handle)));
        let final_tsv = encode_image_records(&all_records);
        let elapsed = start.elapsed().as_millis();
        let count = all_records.len();

        let di = device.device_info();
        let is_samsung = di.vendor_extension_desc.to_lowercase().contains("samsung.com/kies")
            || di.manufacturer.to_lowercase().contains("samsung");
        let restricted = count == 0 && is_samsung;

        lin_log!(crate::lin_logger::LEVEL_INFO, "mtp_bridge", "Indexing complete for {}: {} images in {} (restricted={})", serial, count, crate::lin_logger::format_duration(elapsed as u64), restricted);

        let trial2_total = trial2_cumulative.load(Ordering::Relaxed);
        if trial2_total > 0 && trial2_total != count {
            lin_log!(crate::lin_logger::LEVEL_WARN, "mtp_bridge",
                "Warning: Trial 2 sent {} images, but final index has {}",
                trial2_total, count
            );
        }

        let mut mounts = MOUNTS.lock().unwrap();
        if let Some(entry) = mounts.get_mut(&serial) {
            entry.image_index_tsv = final_tsv;
            entry.image_count = count;
            entry.index_state = IndexState::Ready;
        }
        drop(mounts);

        if let Some(ref vm) = vm {
            let _ = with_jni_env(vm, |env| {
                notify_indexing_ready(env, &serial, count, elapsed.try_into().unwrap_or(0), restricted);
                Ok(())
            });
        }
    });
}

pub(crate) fn with_jni_env<F, R>(vm: &JavaVM, f: F) -> Result<R, jni::errors::Error>
where
    F: FnOnce(&mut JNIEnv) -> Result<R, jni::errors::Error>,
{
    if let Ok(mut env) = vm.get_env() {
        return f(&mut env);
    }
    let mut last_err = None;
    for attempt in 0..3 {
        match vm.attach_current_thread() {
            Ok(mut guard) => return f(&mut guard),
            Err(e) => {
                tracing::warn!(
                    "with_jni_env: attach_current_thread attempt {}/3 failed: {}",
                    attempt + 1, e
                );
                last_err = Some(e);
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }
    Err(last_err.unwrap_or(jni::errors::Error::JavaException))
}

fn notify_indexing_progress(env: &mut JNIEnv, serial: &str, count: usize) {
    let json = serde_json::json!({
        "serial": serial,
        "count": count,
    });
    call_mtp_callback(env, "onIndexingProgress", serial, &json.to_string());
}

fn notify_indexing_batch(env: &mut JNIEnv, serial: &str, cumulative: usize, batch_tsv: &str) {
    let json = serde_json::json!({
        "serial": serial,
        "cumulative_count": cumulative,
        "batch_tsv": batch_tsv,
    });
    call_mtp_callback(env, "onIndexingBatch", serial, &json.to_string());
}

fn notify_indexing_ready(env: &mut JNIEnv, serial: &str, total: usize, elapsed_ms: u64, restricted: bool) {
    let status = if restricted { "Restricted" } else { "Ready" };
    let json = serde_json::json!({
        "serial": serial,
        "total_count": total,
        "elapsed_ms": elapsed_ms,
        "status": status,
        "restricted_mode": restricted,
    });
    call_mtp_callback(env, "onIndexingReady", serial, &json.to_string());
}

fn notify_indexing_failed(env: &mut JNIEnv, serial: &str, message: &str) {
    let json = serde_json::json!({
        "serial": serial,
        "message": message,
    });
    call_mtp_callback(env, "onIndexingFailed", serial, &json.to_string());
}

fn call_mtp_callback(env: &mut JNIEnv, method: &str, serial: &str, json: &str) {
    let cls = match env.find_class("com/soufianodev/lingallery/native/mtp/NativeMtpBridge") {
        Ok(c) => c,
        Err(e) => { tracing::warn!("find_class NativeMtpBridge: {:?}", e); return; }
    };
    let j_serial = match env.new_string(serial) {
        Ok(s) => s,
        Err(_) => return,
    };
    let j_json = match env.new_string(json) {
        Ok(s) => s,
        Err(_) => return,
    };
    if let Err(e) = env.call_static_method(
        cls,
        method,
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[(&j_serial).into(), (&j_json).into()],
    ) {
        tracing::warn!("{} {}: {:?}", method, serial, e);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeUnmountDevice(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jboolean {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    if let Some(cancelled) = CANCEL_FLAGS.lock().unwrap().remove(&serial) {
        cancelled.store(true, Ordering::Relaxed);
    }

    let entry = {
        let mut mounts = MOUNTS.lock().unwrap();
        mounts.remove(&serial)
    };

    match entry {
        Some(mount_entry) => {
            let mp = mount_entry.mount_path.to_string_lossy().to_string();
            let _ = std::process::Command::new("fusermount")
                .args(["-u", &mp])
                .output();
            tracing::info!("Unmounted MTP device {}", serial);
            JNI_TRUE
        }
        None => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_soufianodev_lingallery_native_mtp_NativeMtpBridge_nativeCleanupDevice(
    mut env: JNIEnv,
    _class: JClass,
    serial_jstr: JString,
) -> jlong {
    let serial: String = match env.get_string(&serial_jstr) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    CANCEL_FLAGS.lock().unwrap().remove(&serial);

    let bytes_freed = {
        let mut mounts = MOUNTS.lock().unwrap();
        mounts.remove(&serial)
            .map(|e| e.image_index_tsv.len() as jlong)
            .unwrap_or(0)
    };

    tracing::info!(
        "Deep native cleanup for {}: {} bytes freed",
        serial,
        bytes_freed
    );

    let json = serde_json::json!({
        "serial": serial,
        "bytes_freed": bytes_freed,
    });
    call_mtp_callback(
        &mut env,
        "onDeviceCleanupComplete",
        &serial,
        &json.to_string(),
    );

    bytes_freed
}

fn call_void_static(env: &mut JNIEnv, class_name: &str, method: &str, sig: &str, arg: &str) {
    let cls = match env.find_class(class_name) {
        Ok(c) => c,
        Err(e) => { tracing::warn!("find_class {}: {:?}", class_name, e); return; }
    };
    let j_arg = match env.new_string(arg) {
        Ok(s) => s,
        Err(_) => return,
    };
    if let Err(e) = env.call_static_method(cls, method, sig, &[(&j_arg).into()]) {
        tracing::warn!("call_static {} {}: {:?}", method, arg, e);
    }
}

pub fn notify_device_detected(env: &mut JNIEnv, json: &str) {
    call_void_static(
        env,
        "com/soufianodev/lingallery/native/mtp/NativeMtpBridge",
        "onDeviceConnected",
        "(Ljava/lang/String;)V",
        json,
    );
}

pub fn notify_device_disconnected(env: &mut JNIEnv, serial: &str) {
    call_void_static(
        env,
        "com/soufianodev/lingallery/native/mtp/NativeMtpBridge",
        "onDeviceDisconnected",
        "(Ljava/lang/String;)V",
        serial,
    );
}

pub fn notify_device_error(env: &mut JNIEnv, serial: &str, message: &str) {
    let cls = match env.find_class("com/soufianodev/lingallery/native/mtp/NativeMtpBridge") {
        Ok(c) => c,
        Err(e) => { tracing::warn!("find_class NativeMtpBridge: {:?}", e); return; }
    };
    let j_serial = match env.new_string(serial) {
        Ok(s) => s,
        Err(_) => return,
    };
    let j_msg = match env.new_string(message) {
        Ok(s) => s,
        Err(_) => return,
    };
    if let Err(e) = env.call_static_method(
        cls,
        "onDeviceError",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[(&j_serial).into(), (&j_msg).into()],
    ) {
        tracing::warn!("onDeviceError {} {}: {:?}", serial, message, e);
    }
}
