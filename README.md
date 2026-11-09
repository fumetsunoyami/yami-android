# Yami - Ultra-Low-Latency Game Streaming Client for Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Base](https://img.shields.io/badge/Base-Artemis%20v20.3.0--experimental.9-purple.svg)](https://github.com/ClassicOldSong/moonlight-android)

**Yami** is an open-source Android game streaming client for [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine) (forked from **Artemis** / **Moonlight Android**).

While retaining all the powerful productivity and virtualization capabilities of Artemis, **Yami** introduces groundbreaking optimizations designed specifically to eliminate hardware decoding latency and down-throttling on **Samsung Exynos** devices (e.g. Galaxy Tab S9 FE with Exynos 1380, Exynos 1480, 2200, 2400) and devices with aggressive DVFS power-saving governors.

---

## ⚡ The Exynos Hardware Decoder Fix (Giả lập ghi hình)

### The Problem with Exynos & One UI
On Samsung Exynos devices, the hardware video decoder component (MFC - Multi-Format Codec: `c2.exynos.hevc.decoder`) employs an aggressive dynamic voltage and frequency scaling (DVFS) algorithm. When only decoding a single network video stream, One UI's power governor assumes the workload is lightweight video playback (like YouTube 60fps) and aggressively throttles the MFC clock down to 100MHz–200MHz.

**The result:**
- Decoding latency sits between **17ms – 25ms**, frequently spiking to **50ms – 60ms** during fast scenes.
- Frame pacing becomes stuttery even with a perfect Wi-Fi 6 local network.

### The Yami Solution: 4-Mode Hardware Booster
Yami adds a dedicated **"Giả lập ghi hình" (Screen Recorder Simulation & Hardware Booster)** suite directly at the top of **Video Settings**, offering 4 specialized modes:

| Mode | System Permission | Performance / Latency | Mechanism |
| :--- | :---: | :---: | :--- |
| **1. Tắt (Off)** | None | 17–25ms (Stock) | Standard Artemis/Moonlight decoding pipeline. |
| **2. Encoder + Màn hình ảo (Recommended)** | **None** | **~8.45ms** (Peak ~11ms) | Internal 720p60 hardware encoder fed by an active real-time dynamic frame generator. Zero bytes written to storage. Forces Exynos MFC governor to 845MHz without ANY prompts! |
| **3. Giả lập quay màn hình** | One UI System Permission | **~9.05ms** (Peak ~15ms) | Creates a system-level `MediaProjection` mirror that discards output buffers in RAM, activating One UI's native screen recording performance governor. |
| **4. Quay màn hình thật** | One UI System Permission | Real-time Recording | Records your live game session directly to `Movies/Yami` in MP4 format with configurable resolution (Native capped, 1080p, 720p). |

---

## 🛠️ Key Architectural Improvements in Yami

1. **Active 60fps Feeder Pipeline:**
   - Unlike basic virtual displays that remain idle (0 fps rendered), Yami's internal booster feeds dynamic color-cycling canvases into the hardware encoder at a steady 60fps. This provides continuous real-time load, compelling the Exynos DVFS governor to sustain maximum clock frequencies.
2. **Fixed Artemis External Display Collision Bug:**
   - Upstream Artemis actively scanned `DisplayManager` on startup to attach secondary/virtual displays for Apollo. When screen recording was engaged before streaming, Artemis mistook the recording virtual display for an external monitor and crashed or hijacked the display.
   - Yami separates virtual display types, uses deferred booster initialization (waiting 600ms after the video surface and decoder are 100% stabilized), and ensures safe teardown.
3. **Android 14+ / One UI 6+ Fullscreen Isolation:**
   - Employs `MediaProjectionConfig.createConfigForDefaultDisplay()` to prevent the Android 14 "Share a single app" prompt from minimizing and breaking the game's native OpenGL/Vulkan rendering surface.
4. **Android 14 Foreground Service Security Compliance:**
   - Resolved AOSP race conditions that previously triggered `ForegroundServiceDidNotStartInTimeException` and `SecurityException`.
5. **Session Permission State Machine:**
   - Built-in session state management eliminates redundant permission prompt loops when launching stream sessions.

---

## 🎮 Inherited Artemis Features

Yami carries over all enhancements from Artemis (Moonlight Noir):
- **Custom Virtual Buttons**: Import/export layouts, custom sizes, touch transparency, and button mappings.
- **Advanced Mouse & Touchpad Modes**: Normal mouse, multi-touch, trackpad with tap/scroll, local cursor mode.
- **Apollo Virtual Display Integration**: Seamless dynamic resolutions, server commands, and clipboard synchronization.
- **External Monitor & Samsung DeX Support**: Multi-screen streaming and DeX trackpad optimizations.
- **Custom Resolutions & Bitrates**: Unlock arbitrary resolutions and high-bitrate streaming.
- **Display On Top Mode**: Optimized layout for foldables and dual-screen tablets.
- **Side-by-Side (SBS) 3D**: Stereoscopic 3D support for XR glasses and 3D displays.
- **Comprehensive Controller Support**: Joycon D-pad, device vibration motor passthrough, and gamepad diagnostics.

---

## 🇻🇳 Hướng dẫn sử dụng cho người dùng Samsung Exynos (Tiếng Việt)

Đối với các thiết bị chạy chip Samsung Exynos (như **Galaxy Tab S9 FE**, Galaxy S/A series với Exynos 1380, 1480, 2200, 2400...):

1. Mở app **Yami**, vào biểu tượng **Cài đặt (Settings)** $\rightarrow$ **Cài đặt video (Video Settings)**.
2. Tại mục đầu tiên **"Giả lập ghi hình"**, chọn:
   - **`Encoder + Màn hình ảo (Không cần quyền)`** *(Khuyên dùng nhất)*: 
     - Mang lại độ trễ giải mã cực thấp **~8.4ms** (so với 17–25ms mặc định).
     - **Không cần cấp quyền**, không hiện bất kỳ thông báo làm phiền nào, vào game là tự động kích xung.
   - **`Giả lập quay màn hình (Cần quyền One UI)`**:
     - Hoạt động tương tự cơ chế quay màn hình One UI để kích xung (~9.0ms), tự hủy buffer trong RAM và không tốn dung lượng máy.
   - **`Quay màn hình thật (Lưu file MP4)`**:
     - Cho phép lưu lại video gameplay vào thư mục `Bộ nhớ trong/Movies/Yami` với độ phân giải tùy chọn (Native, 1080p, 720p).

> [!TIP]
> **Mẹo đạt độ trễ kỷ lục (7.2ms):** 
> Để Yami ở chế độ *Tắt* $\rightarrow$ Kéo thanh thông báo One UI xuống bấm *Ghi màn hình* $\rightarrow$ Bấm nút *Tạm dừng (Pause)* trên thanh điều khiển One UI. Hệ thống sẽ giữ nguyên mức xung cao nhất của chip cho Yami giải mã mà không tốn tài nguyên ghi hình!

---

## 🔨 Building from Source

### Prerequisites
- [Android Studio Ladybug / Meerkat](https://developer.android.com/studio) (or newer)
- Android SDK Platform 34 / 36
- Android NDK (r26+ recommended)
- JDK 17, 21, or 25
- Gradle 8.14+

### Build Steps
1. Clone the repository and initialize submodules:
   ```bash
   git clone --recursive https://github.com/fumetsunoyami/yami-android.git
   cd yami-android
   git submodule update --init --recursive
   ```

2. Create `local.properties` in the project root:
   ```properties
   sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
   ndk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk\\ndk\\<ndk-version>
   ```

3. Build the non-root debug/release APK using Gradle:
   ```bash
   # Build Debug APK
   ./gradlew assembleNonRoot_gameDebug

   # Build Release APK
   ./gradlew assembleNonRoot_gameRelease
   ```
   The compiled APK will be located in `app/build/outputs/apk/nonRoot_game/`.

---

## 👥 Credits & Acknowledgments

- **Artemis (Moonlight Noir)** by [ClassicOldSong](https://github.com/ClassicOldSong) - Base client and virtualization architecture.
- **Moonlight Android** by [Cameron Gutman](https://github.com/cgutman) and the Moonlight Stream team.
- **Apollo** & **Sunshine** by the open-source community.
- **Yami** additions by [Viet Le](https://github.com/fumetsunoyami) - Exynos MFC hardware clock booster, active frame feeder pipeline, and Android 14+ compatibility fixes.

---

## 📄 License

Yami is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE.txt](LICENSE.txt) file for details.
