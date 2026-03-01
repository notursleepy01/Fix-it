# StorageFiller

An Android background app that keeps your device storage filled to within a configurable free-space margin — automatically, silently, surviving reboots.

---

## What it does

1. On first boot (or after install/update), a **foreground service** runs once and:
   - Checks total available storage on the best writable volume (external app-private → internal app storage)
   - Creates / resizes a single binary filler file (`filler.bin`) so that exactly **`KEEP_FREE_MB`** (default 100 MB) of free space remains
2. **WorkManager** then schedules a periodic re-check every `CHECK_INTERVAL_HOURS` (default 1 h)
3. A **boot receiver** re-triggers the service on every reboot (including before unlock via `LOCKED_BOOT_COMPLETED`)

---

## Configuration (`.env`)

Edit `.env` before building:

```env
APK_NAME=StorageFiller          # Output APK filename and project name
APP_ID=com.storagefiller.app    # Android application ID (package name)
VERSION_CODE=1
VERSION_NAME=1.0.0

KEEP_FREE_MB=100                # How many MB to leave free
CHECK_INTERVAL_HOURS=1          # How often WorkManager re-checks (min 15 min enforced by OS)
```

---

## Building

### Prerequisites
- JDK 17+
- Gradle 8.4+ installed (`brew install gradle` / `sdk install gradle 8.4` / [gradle.org](https://gradle.org/install/))
- Android SDK with build tools (or Android Studio)
- Set `ANDROID_HOME` / `ANDROID_SDK_ROOT`

> **No `gradlew`** — this project uses your system `gradle` directly.

### Build (debug)
```bash
./build.sh
# APK → app/build/outputs/apk/debug/<APK_NAME>-debug.apk
```

### Build (release, unsigned)
```bash
./build.sh release
# APK → app/build/outputs/apk/release/<APK_NAME>-release.apk
```

### Clean
```bash
./build.sh clean
```

### Or use Gradle directly
```bash
gradle assembleDebug
gradle assembleRelease
```

---

## Installing

```bash
adb install -r app/build/outputs/apk/debug/<APK_NAME>-debug.apk
```

No launcher icon will appear — the app runs entirely in the background.

To verify it is running after install, trigger the boot receiver manually:
```bash
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED \
    -n com.storagefiller.app/.BootReceiver
```

Check logs:
```bash
adb logcat -s FillStorageService StorageHelper BootReceiver StorageWorker
```

---

## Architecture

```
BootReceiver  ──on boot──►  FillStorageService  ──fills file──►  filler.bin
                                    │
                                    └──schedules──►  StorageWorker (WorkManager periodic)
                                                          │
                                                          └──every N hours──►  StorageHelper.fillStorage()
```

| Component | Role |
|---|---|
| `BootReceiver` | `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receiver |
| `FillStorageService` | Short-lived foreground service; prevents OS kill during write |
| `StorageWorker` | WorkManager `PeriodicWorkRequest`; battery-friendly periodic re-check |
| `StorageHelper` | Pure utility: storage stats, file sizing, sparse-file write |

---

## Permissions used (all automatic — no runtime prompts)

| Permission | Why |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Start on boot |
| `FOREGROUND_SERVICE` | Required for foreground service (API 26+) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Required foreground service type declaration (API 34+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Declared so battery optimisation can be disabled by user/MDM if needed |
| `WAKE_LOCK` | Used internally by WorkManager |

No storage permissions are declared or requested — the app only writes to its own private app directories (`getExternalFilesDir` / `getFilesDir`), which require no permissions.

---

## Notes

- The filler file is a **sparse file** (via `RandomAccessFile.setLength()`), so the write is near-instant regardless of size on most file systems.
- If available space is already ≤ `KEEP_FREE_MB`, the filler file is deleted and nothing is written.
- The filler file is re-checked and resized (not fully rewritten) if it drifts by more than 1 MB.
- The app has **no launcher activity** and **no launcher icon**.
