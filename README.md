<div align="center">

  <img src="https://github.com/rukamori/ArchiveTune/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" alt="Last-Archive-Wave-Tune Logo" style="border-radius: 22%">

  <h1>Last-Archive-Wave-Tune</h1>

  <p align="center">
    <strong>The Ultimate Android Music Experience: 24-bit Hi-Res Lossless FLAC, Dolby Atmos, and YouTube Music Integration.</strong>
    <br />
    <em>Merging LastWave's Lossless Streaming Engine &amp; .lwp Plugin Ecosystem with ArchiveTune's Material 3 Design.</em>
  </p>

  <div align="center">
    <img src="https://img.shields.io/badge/Release-v1.0.0--lastwave-6366f1?style=for-the-badge&labelColor=1e1e2e&logo=github" alt="Latest Version" />
    <img src="https://img.shields.io/badge/Audio-24bit_192kHz_FLAC-green?style=for-the-badge&labelColor=1e1e2e&logo=flac" alt="24-bit FLAC" />
    <img src="https://img.shields.io/badge/Spatial-Dolby_Atmos-5865F2?style=for-the-badge&labelColor=1e1e2e" alt="Dolby Atmos" />
    <img src="https://img.shields.io/badge/License-GPLv3-6366f1?style=for-the-badge&labelColor=1e1e2e" alt="GPLv3 License" />
    <img src="https://img.shields.io/badge/Toolkit-Jetpack_Compose-4285f4?style=for-the-badge&logo=jetpack-compose&labelColor=1e1e2e" alt="Jetpack Compose" />
  </div>

</div>

<hr />

**Last-Archive-Wave-Tune** combines the audio quality of **LastWave** with the UI and features of **ArchiveTune**. It integrates LastWave's Hi-Res Lossless FLAC streaming engine, Dolby Atmos spatial audio detection, and `.lwp` addon ecosystem directly into a Material 3 Jetpack Compose application.

---

## 🔥 Key Features

<div align="center">

<table>
  <tr>
    <td width="50%" valign="top">
      <div align="left">
        <h3>🎧 Lossless Audio &amp; LastWave Engine</h3>
        <ul>
          <li><strong>24-bit / 192kHz Hi-Res FLAC</strong> streaming directly from LastWave addons</li>
          <li><strong>16-bit Lossless CD Quality</strong> playback support</li>
          <li><strong>Dolby Atmos</strong> spatial audio detection and hardware output</li>
          <li><strong>.lwp Plugin &amp; Addon Manager</strong>: Manage streaming addons and test endpoint health</li>
          <li>HMAC-SHA256 request signing with official LastWave protocol</li>
        </ul>
      </div>
    </td>
    <td width="50%" valign="top">
      <div align="left">
        <h3>🎛️ Playback &amp; Multi-Source Control</h3>
        <ul>
          <li><strong>Unified Audio Source Order</strong>: LastWave preferred, with fallback to YouTube, Deezer, Tidal, Telegram, and local files</li>
          <li><strong>In-App Source Selector</strong>: Switch playback sources per track on the fly</li>
          <li><strong>Fast $O(1)$ Seeking</strong>: Drag progress bar without buffering delays</li>
          <li><strong>Live Synchronized Lyrics</strong>: Real-time word-by-word synced lyrics</li>
          <li><strong>Source Debug Logger</strong>: View live stream resolution logs in-app</li>
        </ul>
      </div>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <div align="left">
        <h3>🎨 Interface &amp; Design</h3>
        <ul>
          <li>Modern <strong>Material 3</strong> design language</li>
          <li>Album-art dynamic color extraction</li>
          <li>Multiple player layout styles and customizable player backgrounds</li>
          <li>Responsive layouts optimized for phones, foldables, and tablets</li>
        </ul>
      </div>
    </td>
    <td width="50%" valign="top">
      <div align="left">
        <h3>📊 Discovery &amp; Synchronization</h3>
        <ul>
          <li>YouTube Music account sign-in &amp; playlist sync</li>
          <li>Spotify playlist import</li>
          <li>Last.fm scrobbling &amp; ListenBrainz history logging</li>
          <li>Discord Rich Presence integration</li>
        </ul>
      </div>
    </td>
  </tr>
</table>

</div>

---

## 📥 Downloads

The pre-built release APK is generated via Gradle and ready for installation:

- **Path:** `app/build/outputs/apk/gmsMobileUniversal/release/app-gms-mobile-universal-release.apk`
- **Application ID:** `com.lastarchivewavetune.app`
- **Application Name:** `Last-Archive-Wave-Tune`

---

## 🛠️ Open-Source Credits & Acknowledgments

**Last-Archive-Wave-Tune** is made possible thanks to these open-source projects:

- **[LastWave](https://github.com/pm-official-id/last-archive-wave-tune)** by **[pm-official-id](https://github.com/pm-official-id)** — For the Hi-Res Lossless FLAC streaming engine, Dolby Atmos detection, `.lwp` plugin specification, and addon API signing protocol.
- **[ArchiveTune](https://github.com/rukamori/ArchiveTune)** by **[Rukamori](https://github.com/rukamori)** and **[vossgraves](https://github.com/vossgraves)** — For the base Material 3 player interface, lyrics translation, and stats engine.
- **[InnerTune](https://github.com/zaneschepke/InnerTune)** by **[zaneschepke](https://github.com/zaneschepke)** — For the core Android playback architecture and service foundation.
- **[SimpMusic](https://github.com/maxrave-dev/SimpMusic)** by **[maxrave-dev](https://github.com/maxrave-dev/SimpMusic)** — For the lyrics API provider.
- **[Metrolist](https://github.com/mostafaalagamy/Metrolist)** by **[Mostafa Alagamy](https://github.com/mostafaalagamy/Metrolist)** — For UI components and audio visualizer design.

---

## ⚖️ License & Legal Disclaimer

- Licensed under the **GNU General Public License v3.0 (GPLv3)**.
- **Last-Archive-Wave-Tune** is an independent third-party client not affiliated with Google, YouTube, or Tidal.
