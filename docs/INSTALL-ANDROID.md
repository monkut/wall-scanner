# Installing Wall Scanner on Android

Wall Scanner is installed from its GitHub release as an APK file (it is not on Google Play).

## Requirements

- Android 9 or newer.
- A phone that supports ARCore ([list of supported devices](https://developers.google.com/ar/devices)) with a depth
  sensor or depth from motion (tested on a Pixel 9a). On the first scan the app asks Google Play to install or
  update **Google Play Services for AR**; accept it.
- About 250 MB free for the app, plus room for scans (tens of MB each) and climb videos (~60 MB per filmed minute).

## Install

1. **Download the APK.** On the phone, open the release on GitHub (Releases → the newest release) and tap
   `wall-scanner-<version>.apk` under **Assets**. The repository is private: sign in to GitHub in the phone's
   browser first, or download the file on a computer and copy it to the phone (USB, Google Drive, …).
2. **Allow the install.** Open the downloaded file (from the browser's downloads or the Files app). Android asks
   to allow installs from that app the first time: **Settings → Allow from this source**, then go back.
3. **Install.** Tap **Install**. Google Play Protect may warn about an app from an unknown developer: tap
   **More details → Install anyway**. (Play Protect may offer to scan it; that is fine.)
4. **Open** Wall Scanner. It asks for each permission when it first needs it:
   - **Camera**: scanning walls and filming climbs.
   - **Location** (optional): to place a gym on the map the first time you scan there.
   - **Microphone** (optional): sound in climb videos; without it, videos are silent.

## Updating

Install the newer APK the same way, over the installed app. Scans, routes, sends and climb videos are kept.

If Android says **App not installed** or **package conflicts with an existing package**, the installed copy was
signed with a different key (for example a development build installed from a computer). Uninstalling it deletes
all scans and logs on the phone. The app has no import: an export is an off-phone copy and cannot be loaded back
into the app. To keep a copy, in **Capture** long-press each scan → **Share** (the zip holds the scan and its
sends; climb videos are shared one by one from playback). Then uninstall and install the release.

## Check the download (optional)

Each release lists the APK's SHA-256 checksum. On a computer:

```bash
sha256sum wall-scanner-<version>.apk   # macOS: shasum -a 256 wall-scanner-<version>.apk
```

The output must match the checksum in the release notes (and the `.sha256` file next to the APK).

## Install from a computer (adb)

With USB debugging on (Settings → About phone → tap **Build number** seven times → Developer options → **USB
debugging**):

```bash
adb install -r wall-scanner-<version>.apk
```

`-r` replaces the installed app and keeps its data (same signing key, see Updating).

## Uninstall

Settings → Apps → Wall Scanner → **Uninstall**. This deletes every scan, route, send and video on the phone.
