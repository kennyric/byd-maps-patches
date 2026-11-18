# BYD Maps Patches

Google Maps-only Morphe patch bundle for **Android Auto** that displays your BYD's live state of charge and estimated arrival battery (`86% → 11%`) from [byd-open-remote](https://github.com/kennyric/byd-open-remote), while keeping Google Maps' **Material You** design.

Forked from [nl-morphe-patches](https://gitlab.com/johninnld/nl-morphe-patches) and narrowed down to **Google Maps only** (GPL-3.0).

---

## Quick Start: Add Repo to Morphe

### Option 1 — Morphe Manager (on Android)
1. Install **[Morphe Manager](https://morphe.software/)**.
2. Add this repository as a patch source (either method):
   - **One-tap**: Open `https://morphe.software/add-source?github=kennyric/byd-maps-patches` on your phone.
   - **Manual**: Switch to the **Sources** tab, tap **`+`**, and paste:
     ```
     https://github.com/kennyric/byd-maps-patches
     ```
3. Download **Google Maps `26.44.06.1006895251`** (versionCode `1068856906`, `minSdk 29`) from APKMirror.
4. In Morphe Manager, select the Google Maps APK, keep the default patches (which include **`BYD battery and range in the navigation card`** and **`Native navigation for any Android Auto host`**), and patch.
   - *Optional*: Enable **`Add microG-RE support`** if you want Google account sign-in via MicroG-RE (package becomes `org.ungoogled.android.apps.maps.microg`).
   - *Material You*: `Black theme`, `Rectangle shapes`, and `Zoom controls in navigation` are **off by default** so Maps keeps its rounded Material You look.

### Option 2 — Morphe Desktop CLI (on PC)
Download the latest `patches-*.mpp` from [Releases](https://github.com/kennyric/byd-maps-patches/releases/latest) and run:
```bash
java -jar morphe-desktop-2.2.0-all.jar patch \
  -p patches-1.93.0.mpp \
  -o maps-byd.apk \
  GoogleMaps_26.44.06.apk
```

---

## Android Auto & `byd-open-remote` Setup

1. **Install `byd-open-remote` (`≥ 1.1.0`)** on your phone and sign in to your BYD account.
   - If you enabled `Add microG-RE support`, set the target package in `byd-open-remote` to `org.ungoogled.android.apps.maps.microg` (otherwise the default `org.ungoogled.android.apps.maps` matches).
2. **Install the patched Maps APK via KingInstaller + Shizuku** so Google Play Store is recorded as the installer (otherwise Android Auto hides third-party navigation apps):
   - Alternatively via ADB: `adb install -i com.android.vending -r maps-byd.apk`
3. Open phone **Settings → Android Auto → Customize launcher** and enable **Ungoogled Maps**.

---

## How the BYD Battery Pill Works

- **Dynamic Day/Night pill**: During active navigation on Android Auto, a rounded Material pill sits above the bottom-left ETA card (`current% → arrival%`), automatically adapting to Day (frosted white) and Night (dark slate) with green (`≥ 30%`), amber (`15–29%`), and red (`< 15%`) SoC text.
- **Freshness**: Data older than 15 minutes is hidden automatically so stale readings are never shown.
- **Diagnostics**:
  ```bash
  adb logcat -s BydTelemetry BydVehicle
  ```

---

## License

GPL-3.0 — see [LICENSE](LICENSE). Upstream Android Auto navigation adapter and base Maps patches by [johninnl / nl-morphe-patches](https://gitlab.com/johninnld/nl-morphe-patches).
