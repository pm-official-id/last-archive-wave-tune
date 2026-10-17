# Last-Archive-Wave-Tune — Core Architecture & Developer Handover

Welcome to **Last-Archive-Wave-Tune**, a pristine fork of **ArchiveTune** enhanced with **LastWave**'s high-fidelity Lossless FLAC streaming engine, In-App YouTube Video Player, High-Speed Downloader, and `.lwp` Addon Plugin System.

---

## 🔗 Origin Projects & Repositories

- **ArchiveTune (UI & Design System)**: [https://github.com/NatuneGroup/ArchiveTune](https://github.com/NatuneGroup/ArchiveTune)
- **LastWave-Native (Lossless Engine & Addons)**: [https://github.com/Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native)

---

## 📂 Project Structure & Modules

- **`:app`**: Main Android application module containing UI screens, themes, ViewModels, and services.
- **`:core`**: NewPipeExtractor-based InnerTube client and core networking layer (`vossgraves/core`).
- **`:lyrics:*`**: Modular lyric providers (LRCLIB, SimpMusic, Kugou, BetterLyrics, etc.).
- **`:jiosaavn`, `:spotifycore`, `:shazamkit`, `:lastfm`, `:musixmatch`**: Native ArchiveTune metadata and streaming catalog modules.

---

## 🔑 Key Invariants & Package Identity

- **Application ID / Package**: `com.lastarchivewavetune.app` (Debug variant: `com.lastarchivewavetune.app.debug`).
- **Isolation**: Configured with a unique package name so it installs and runs side-by-side with original ArchiveTune and LastWave with zero conflicts.
- **UI & Themes**: Preserves 100% of ArchiveTune's original visual themes, Material You styling, and layout architecture.

---

## ⚡ Integrated LastWave Features

1. **Lossless FLAC Master Streaming**:
   - `LosslessMusicApi` & `InnerTubeMusicApi` integration for 24-bit/192kHz FLAC master streams alongside ArchiveTune's default providers (JioSaavn, Spotify Core, Last.fm).
2. **In-App YouTube Video Player & PiP**:
   - Media3 `ExoPlayer` video player using `MergingMediaSource` (video + audio adaptive streams merged for synced sound), 100% ad-free playback, and native Picture-in-Picture (PiP) mode support.
3. **High-Speed Downloader**:
   - `TrackDownloadManager` with `128 KB` stream buffers, ID3/FLAC tag embedding, `.lrc` companion lyrics saver, and external downloader intent hand-off (ADM, IDM, 1DM).
4. **Addon Plugin Module System (`.lwp`)**:
   - `ModuleManager` & `ModulePlaybackResolver` for importing and executing external `.lwp` addon provider scripts.

---

## 🚀 How to Open in Android Studio (New Window)

1. Open **Android Studio**.
2. Click **File → Open** (or **Open Project** on the welcome screen).
3. Navigate to your **Desktop** and select the folder:
   `last-archive-wave-tune`
4. Click **Open in New Window**.
5. Let Gradle sync complete, then build and run (`./gradlew assembleDebug`).
