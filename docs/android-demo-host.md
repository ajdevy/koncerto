# Android demo host

The Android recorder needs a device `adb` can reach and a Maestro binary. This describes the setup
in use on the Contabo VPS, which has **no KVM** — Contabo does not expose nested virtualisation, so
the standard Android emulator cannot run there.

## Why not the emulator

`/dev/kvm` is absent and `/proc/cpuinfo` reports no `vmx`/`svm` flags. The emulator falls back to
QEMU TCG software emulation, where an x86 system image takes tens of minutes to boot and renders a
slideshow — useless for a demo whose entire output is a watchable video.

## What is used instead: redroid

[redroid](https://github.com/remote-android/redroid-doc) runs Android in a Docker container against
the **host kernel's binder**, so it needs no virtualisation at all. It exposes adb over TCP and
behaves like an ordinary device for both `adb` and Maestro.

### One-time host setup

```bash
# binder ships as a module (CONFIG_ANDROID_BINDER_IPC=m) but the modules package is not installed
sudo apt-get install -y linux-modules-extra-$(uname -r)
sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
sudo mkdir -p /dev/binderfs
sudo mount -t binder binder /dev/binderfs
```

`/dev/binderfs` should then contain `binder`, `hwbinder` and `vndbinder`. To survive a reboot, add
`binder_linux` to `/etc/modules-load.d/` with its `devices=` option in `/etc/modprobe.d/`, and the
binderfs mount to `/etc/fstab`.

### Running the device

```bash
sudo docker run -itd --rm --privileged --name redroid \
  -v /home/ubuntu/redroid-data:/data \
  -p 127.0.0.1:5555:5555 \
  redroid/redroid:11.0.0-latest \
  androidboot.redroid_width=720 androidboot.redroid_height=1280 androidboot.redroid_dpi=320
```

Bound to loopback deliberately — adb has no authentication, so the port must never be exposed.

```bash
sudo apt-get install -y adb
adb connect localhost:5555
adb -s localhost:5555 shell getprop sys.boot_completed   # 1 once booted
```

### Maestro

```bash
sudo apt-get install -y unzip          # the installer needs it and fails unhelpfully without it
curl -Ls https://get.maestro.mobile.dev | bash
export PATH="$PATH:$HOME/.maestro/bin"
```

## Verified on this host

- `adb devices` lists the container as a usable device, and `AdbCommands.selectDevice` picks it.
- `adb shell screenrecord` produces a valid H.264 720x1280 MP4 that `adb pull` retrieves.
- Maestro drives the app and reports a real exit code — 0 for a passing flow, 1 for a failing one.
- A full run of the ticket pipeline's Android path produced a 32s recording of Settings being
  navigated, with every assertion passing.

## Known limits

- `launchApp` cannot start a home app (`com.android.launcher3`) — Maestro reports
  `DeviceCallFailedException: 'launchApp' failed: UNKNOWN`. Demo an ordinary app.
- `clearState: true` fails on apps whose state cannot be cleared; the scenario vocabulary exposes
  `clearState: false` for those.
- redroid images are Android 11-14 AOSP without Google Play services. An app that requires GMS needs
  a different host.
