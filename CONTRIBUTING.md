# Contributing to LinGallery

## Requirements

### Docker (recommended)

Docker Engine 23.0 or later with BuildKit (enabled by default).

### Manual setup (without Docker)

#### Build requirements

- Linux (Windows and macOS not tested)
- JDK 21
- Rust 1.79 or later
- libfuse3-dev, libusb-1.0-0-dev, pkg-config

#### Runtime requirements

- libfuse3-3 (FUSE library)
- libusb-1.0-0 (USB library)
- OpenGL support (Skia rendering backend)

## Installing Docker

### Any distribution (convenience script)

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

Log out and back in. Verify with `docker run hello-world`.

### Debian / Ubuntu

```bash
sudo apt update
sudo apt install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### Fedora

```bash
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo
sudo dnf install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

### CentOS / RHEL / Rocky Linux

```bash
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

### Arch Linux / Manjaro

```bash
sudo pacman -S docker docker-compose
sudo systemctl enable --now docker
```

### openSUSE

```bash
sudo zypper addrepo https://download.docker.com/linux/suse/docker-ce.repo
sudo zypper install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
```

### Alpine

```bash
sudo apk add docker docker-compose
sudo rc-update add docker boot
sudo service docker start
```

## Quick start

### With Docker

```bash
docker compose -f docker/compose.yaml build
docker compose -f docker/compose.yaml run --rm dev
./gradlew build
```

### Without Docker

```bash
git clone https://github.com/SoufianoDev/LinGallery.git
cd LinGallery
./gradlew build
```

## Docker development environment

The `docker/` directory contains a multi-stage Dockerfile and a Compose file.

### Image

| Component | Source | Version |
|-----------|--------|---------|
| JDK | eclipse-temurin:21-jdk | 21 |
| Rust | rust:1.85-slim | 1.85 |
| Runtime user | lingallery (non-root) | UID 1000 |

### Persistent volumes

| Volume | Mount point | Purpose |
|--------|-------------|---------|
| `gradle-cache` | `/home/lingallery/.gradle` | Gradle dependencies and build cache |
| `cargo-registry` | `/usr/local/cargo/registry` | Cargo crate registry |
| `cargo-git` | `/usr/local/cargo/git` | Cargo git dependency cache |

Volumes persist across container restarts. Dependencies download once.

### Environment variables

| Variable | Value | Purpose |
|----------|-------|---------|
| `GRADLE_USER_HOME` | `/home/lingallery/.gradle` | Gradle cache location |
| `GRADLE_OPTS` | daemon, parallel, caching | Build performance |
| `JAVA_OPTS` | `-Xmx2g` | JVM heap limit |

### Workflow

```bash
# Build the image
docker compose -f docker/compose.yaml build

# Start a shell
docker compose -f docker/compose.yaml run --rm dev

# Run a command directly
docker compose -f docker/compose.yaml run --rm dev ./gradlew build
```

## Run

### With Docker

```bash
docker compose -f docker/compose.yaml run --rm dev ./gradlew run
```

### Without Docker

Install runtime dependencies first (see Requirements > Manual setup).

```bash
./gradlew run
```

The application runs with these JVM defaults (set in `build.gradle.kts`):
- Heap: 2 GB (`-Xmx2g`)
- Stack: 512 KB (`-Xss512k`)
- Rendering: OpenGL via Skiko

## Project structure

```
app/src/main/kotlin/com/soufianodev/lingallery/
  app/                      -- Entry point, dependency wiring, i18n
    Main.kt                 -- Window setup
    App.kt                  -- Root composable, screen routing
    AppModule.kt            -- Service locator
    AppWindow.kt            -- Window state management
    AppConst.kt             -- Constants
    Strings.kt              -- i18n via ResourceBundle
    CrashHandler.kt         -- Global exception handler
    IssueReporter.kt        -- GitHub issue reporting
  model/                    -- Domain models
    Album.kt
    ImageFile.kt
  gallery/                  -- Gallery browsing feature
    GalleryScreen.kt
    GalleryGrid.kt          -- Native Skia thumbnail grid
    AlbumSidebar.kt
    GalleryStateHolder.kt
    GalleryRepository.kt
    GalleryUiState.kt
    data/
      FileIndexer.kt        -- Directory scanner
      FileWatcher.kt        -- File system watcher
      GalleryIndex.kt       -- SQLite cache
  viewer/                   -- Image viewer feature
    ViewerScreen.kt
    ViewerStateHolder.kt
    ImageDisplay.kt         -- Skia image rendering
    CropOverlay.kt
    EditToolbar.kt
    FloatingZoomControl.kt
    SlideshowController.kt
  native/                   -- JNI bridge to Rust
    NativeLibLoader.kt      -- Loads liblingallery_native.so
    NativeScanner.kt        -- Directory scan via Rust
    NativeImagePipeline.kt  -- Image decode via Rust
    EventBus.kt             -- Native event dispatch
    LinLogger.kt            -- Rust logging bridge
    MemoryManager.kt        -- Jemalloc stats
    OwnedSkiaImage.kt       -- Skia image wrapper
    mtp/
      MtpEvent.kt
      NativeMtpBridge.kt
  devices/                  -- Device protocol layer
    core/
      DeviceManager.kt
      DeviceRepository.kt
      DeviceConnection.kt
      DeviceAlbum.kt
      DeviceState.kt
    ui/
      DeviceIssueDialog.kt
      DeviceActivityPresenter.kt
    usb/mtp/
      MtpCallbacks.kt
      MtpProtocol.kt
  shared/                   -- Utilities
    imaging/ImageEditor.kt
    filesystem/
      PathExt.kt
      SafeFileOps.kt
      TrashManager.kt
    desktop/
      Clipboard.kt
      WindowUtil.kt
  ui/                       -- Reusable components
    component/
      LinGallerySnackbar.kt
      Tooltip.kt
      CursorGuard.kt
    theme/
      Color.kt
      Theme.kt
      Icons.kt

app/src/main/rust/
  Cargo.toml                -- Rust dependencies
  Cargo.lock                -- Lockfile (committed)
  src/
    lib.rs                  -- JNI entry point, allocator
    image_pipeline.rs       -- Skia image decoding
    mtp_bridge.rs           -- MTP and FUSE mount
    device_watcher.rs       -- USB polling
    memory_manager.rs       -- Jemalloc trimming
    lin_logger.rs           -- Logging bridge
  include/
    lin_logger.h            -- C header for logging macros
```

## Stack

### Kotlin / JVM

| Component | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 2.4.0 | Language |
| JVM target | 21 | Runtime |
| Compose Multiplatform | 1.11.1 | Desktop UI |
| Material3 | 1.11.0-alpha07 | Design system |
| Gradle | 9.1.0 | Build system |
| Sketch | 4.4.0-beta02 | Image loading |
| Scrimage | 4.3.5 | Image processing |
| metadata-extractor | 2.18.0 | EXIF data |
| SQLite JDBC | 3.49.1.0 | Persistent cache |

### Rust

| Crate | Version | Purpose |
|-------|---------|---------|
| jni | 0.21 | JNI bindings |
| jwalk | 0.8 | Directory traversal |
| mtp-rs | 0.21 | MTP device protocol |
| fuser | 0.17 | FUSE filesystem |
| nusb | 0.2 | USB enumeration |
| tokio | 1 | Async runtime |
| image | 0.25 | Image decoding |
| tikv-jemallocator | 0.7 | Memory allocator |

## Dependencies

### Kotlin / JVM

Managed through the Gradle version catalog at `gradle/libs.versions.toml`.
Add new entries there rather than hardcoding versions in `build.gradle.kts`.

### Rust

Managed through `Cargo.toml` at `app/src/main/rust/Cargo.toml`.
The `Cargo.lock` file is committed.

## Coding conventions

### Kotlin

- Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `kotlin.code.style=official` (set in `gradle.properties`)
- No semicolons
- Prefer null-safe operators over `!!`

### Rust

- Follow the [Rust API Guidelines](https://rust-lang.github.io/api-guidelines/)
- Run `cargo fmt` before committing
- Run `cargo clippy` and address warnings
- `unsafe` blocks require a safety comment
- Errors propagate via `anyhow::Result` in internal code

### JNI bridge

- Native function names follow the `Java_com_soufianodev_lingallery_native_*` pattern
- Kotlin `external` declarations mirror Rust JNI exports
- Rust allocates, Rust frees

## Pull request process

1. Open an issue describing the change before starting work
2. Fork the repository and create a feature branch
3. Make your changes
4. Run `docker compose -f docker/compose.yaml run --rm dev ./gradlew build` to verify
5. Submit a pull request referencing the issue

## Code of conduct

Keep discussions focused on the code. Personal attacks are not tolerated.
