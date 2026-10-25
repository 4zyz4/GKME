# NOTICE

This project is a modified version of **GKME — Gamepad Keyboard Mouse Emulator**.

- Upstream project: <https://github.com/4zyz4/GKME>
- Upstream author: [@4zyz4](https://github.com/4zyz4)
- Upstream license: GNU General Public License v3.0 (see [LICENSE](LICENSE))

## Statement of modification

In accordance with **GNU GPL v3.0, section 5(a)**, this repository carries the
following prominent notice:

> This work is a **modified version** of GKME by 4zyz4.
> Modifications were made by **orson391** starting **2026-10-23**.

Summary of changes relative to the upstream project:

- Re-scoped the project to a CODM-oriented virtual gamepad (Xbox / DS4 / DualSense
  emulation over `/dev/uinput` and `/dev/uhid` via Shizuku).
- Removed the upstream network transport, virtual keyboard/mouse UI, and the
  browser-based layout editor.
- Added dedicated CI/CD workflows for building and releasing APKs.
- Rewrote the documentation (README, project description).
- Changed the Android `applicationId` / `namespace` from `com.zyz4.gkme` to
  `com.orson391.virtualgamepad` so that this fork is a **separate, independently
  installable application** and does not overwrite or impersonate the upstream app.

## Upstream copyright

The original code is Copyright (C) 4zyz4 and is used under the terms of the
GNU General Public License v3.0. The original copyright notice is retained in
[LICENSE](LICENSE) and must not be removed.
