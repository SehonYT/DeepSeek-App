# DeepSeek for Android

**Autonomous DeepSeek AI coding environment running natively on Android with Termux userspace.**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20%7C%20armeabi--v7a-blue?style=flat-square)](https://arm.com)
[![License](https://img.shields.io/badge/License-MIT-orange?style=flat-square)](LICENSE)

---

### Overview

**DeepSeek for Android** brings the power of **DeepSeek Harness** directly to mobile devices.

No PRoot, chroot, or virtualization overhead. DeepSeek runs **bare-metal inside native Android userspace (Bionic)** powered by an integrated Termux bootloader. Driven by DeepSeek V3 and R1 reasoning models, the agent inspects repositories, authors code across multiple files, compiles binaries, and runs tasks completely on-device.

### App Previews

<table align="center">
  <tr>
    <td align="center">
      <a href="docs/assets/preview_1.png"><img src="docs/assets/preview_1.png" width="240" alt="Agent Chat" title="Agent Chat" /></a><br/>
      <sub>Agent Chat</sub>
    </td>
    <td align="center">
      <a href="docs/assets/preview_2.png"><img src="docs/assets/preview_2.png" width="240" alt="Workspaces & Sessions" title="Workspaces & Sessions" /></a><br/>
      <sub>Workspaces &amp; Sessions</sub>
    </td>
  </tr>
  <tr>
    <td colspan="2" align="center">
      <a href="docs/assets/preview_3.png"><img src="docs/assets/preview_3.png" width="240" alt="Integrated Terminal" title="Integrated Terminal" /></a><br/>
      <sub>Integrated Terminal</sub>
    </td>
  </tr>
</table>

### Architecture

- **App Shell:** Native Android application host (`com.dsh`) with foreground service supervisor and hardware integration.
- **Engine Runtime:** DeepSeek Harness Node.js agent runtime running bare-metal via Termux userspace.
- **Bootloader:** Self-extracting Termux Bionic userspace environment with bash, coreutils, python, and git.
- **Web UI:** Full-featured reactive web interface loaded directly in the hardware-accelerated WebView.

### Building via GitHub Actions CI

All release APKs are automatically built and published via GitHub Actions.
To trigger a build:
1. Navigate to **Actions** -> **Build and Release DeepSeek APK**
2. Click **Run workflow**
3. Download the APK matching your device:
   - `deepseek.apk` — Universal (`arm64-v8a` + `armeabi-v7a` + `x86` + `x86_64`)
   - `deepseek-arm64-v8a.apk` — 64-bit ARMv8-A+ split
   - `deepseek-armeabi-v7a.apk` — 32-bit ARMv7-A split
   - `deepseek-x86_64.apk` — 64-bit x86_64 split
   - `deepseek-x86.apk` — 32-bit x86 split
