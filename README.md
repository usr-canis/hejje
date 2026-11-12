# Hejje (ಹೆಜ್ಜೆ)

A private "Strava for my car". Hejje records every drive automatically: it starts when your
phone connects to your car's Bluetooth and stops when it disconnects. *Hejje* is Kannada for
"footprint".

- Trips stay on your phone. No accounts, no cloud, no analytics, no ads.
- The internet is used for three things only: map tiles, place names (optional) and update checks.
- Android 8.0 or newer. Needs Google Play Services for location.

This guide is written for someone who does not code. Everything can be done from a phone.

---

## 1. Install the app

1. On your phone, open this repository's **Releases** page and tap the newest release.
2. Tap the file ending in `.apk` to download it.
3. Open the downloaded file. Android asks whether your browser may install apps: tap
   **Settings**, switch on **Allow from this source**, go back and tap **Install**.
4. Open **Hejje** and follow the short guide. It asks for:
   - **Location**, then **"Allow all the time"**, so a drive can start while the phone is in your pocket.
   - **Notifications**, for the small "Recording" notification and the trip summary.
   - **Nearby devices**, so the app can see your car's Bluetooth connect. Then pick your car.
   - **Battery: unrestricted**, so Android does not put the app to sleep.

After the guide, open **Settings › Recording health**. Every line should have a tick. Any line
with a warning has a **Fix** button.

**Recommended:** in **Settings › Car Bluetooth device**, tap **Link now** while sitting in the
car with the engine on. This lets Android wake the app the moment the car connects.

## 2. Update the app

When a newer version exists, a card appears at the top of the Trips tab (checked at most once a
day; you can also use **Settings › Check for updates**).

1. Tap **Update**. The download shows its progress.
2. Android shows its own **"Update this app?"** screen: tap **Update**.

Android requires that confirmation for every install of an app from outside the Play Store. The
first time, it may also ask you to allow Hejje to install apps.

Your trips are kept when you update. Updates only work if they are signed with the same key as
the installed app, which is why the key backup in step 3 matters.

## 3. One-time setup: signing key

Every release must be signed with your own private key. You create it once. The key is never
shown on screen and never stored in the repository.

Do these steps in your phone's browser at github.com (not the GitHub app).

**A. Invent a password**

Make up a password of at least 16 characters and save it in your password manager. You will
need it if you ever have to restore the key.

**B. Store the password as a secret**

1. Open the repository, then **Settings › Secrets and variables › Actions**.
2. Tap **New repository secret**. Name: `KEYSTORE_PASSWORD`. Secret: your password. Tap **Add secret**.

**C. Create a temporary token**

This lets the setup store the key as a secret for you.

1. Tap your profile picture, then **Settings › Developer settings › Personal access tokens › Fine-grained tokens**.
2. Tap **Generate new token**. Name it `hejje setup`. Expiration: 7 days.
3. Repository access: **Only select repositories**, then choose `hejje`.
4. Permissions › Repository permissions › **Secrets**: **Read and write**.
5. Tap **Generate token** and copy it.
6. Back in the repository's secrets, add another secret. Name: `SETUP_TOKEN`. Secret: the token.

**D. Create the key**

1. Open the repository's **Actions** tab and choose **Create signing key (run once)**.
2. Tap **Run workflow**, type `create` in the box and tap **Run workflow**.
3. Wait about a minute for the green tick.

**E. Back up the key (important)**

1. Open the finished run. Under **Artifacts**, download `hejje-signing-key-backup`.
2. Keep that file in two safe places (for example your password manager and a private drive),
   together with the password from step A.
3. Delete the artifact from the run page once you have it. It is removed automatically after
   one day in any case.

> **Why this matters:** Android only accepts an update signed with the same key as the installed
> app. If the key is lost, you would have to uninstall the app (losing its data unless you
> exported a backup) and install a fresh copy. With the backup file and the password, the key can
> always be restored.

Because this repository is public, other signed-in GitHub users could download the backup file
during that day. It is encrypted with your password, which is why the password must be long.

**F. Clean up**

1. In the repository's secrets, delete `SETUP_TOKEN`.
2. In your profile's token list, delete the `hejje setup` token.

Keep `KEYSTORE_PASSWORD` and `KEYSTORE_BASE64`: releases need both.

*Restoring a key later:* run `base64 -w 0 hejje-release.keystore` on a computer and save the
output as the `KEYSTORE_BASE64` secret, with the password in `KEYSTORE_PASSWORD`.

## 4. Publish a new release

One button:

1. Open the **Actions** tab and choose **Release**.
2. Tap **Run workflow**, type the new version (for example `1.2.0`) and tap **Run workflow**.

About ten minutes later the release appears under **Releases** with the signed APK and notes
made from the changes since the last release. Installed copies of the app then offer the update.

Rules for the version number:

- Three numbers, such as `1.2.0`.
- Always higher than the last one (`1.2.0` → `1.2.1` or `1.3.0`). The workflow refuses a version
  that already exists.

Developers can instead push a tag: `git tag v1.2.0 && git push origin v1.2.0`.

## 5. If drives are not recorded

Open **Settings › Recording health** first. The usual causes:

| What you see | What to do |
|---|---|
| Location is not "Allow all the time" | Tap **Fix**, choose **Allow all the time** |
| Battery is not unrestricted | Tap **Fix**, choose **Allow** / **Unrestricted** |
| Nothing starts when the car connects | Check that the right car is chosen, then tap **Link now** in the car |
| "Last problem" shows a message | It says what Android refused; fix the line it mentions |

You can always start and stop a recording by hand with the round button at the top of the Trips tab.

## 6. Your data

- **Export everything:** Settings › Backup › Export everything. One `.hejje` file with all trips and settings.
- **Import:** Settings › Backup › Import. Accepts a Hejje backup or a GPX file. Duplicates are skipped.
- **Automatic weekly backup:** choose a folder once; the newest few backups are kept.
- **Privacy zones:** routes inside a zone are cut out of share cards and GPX exports. The full
  trip stays on the phone.

---

## For developers

Kotlin, Jetpack Compose, Material 3. `minSdk` 26, `targetSdk` 37. Single `app` module:

| Package | Contents |
|---|---|
| `domain` | Pure Kotlin: GPS clean-up, stats, route simplification, hard-braking detection, edits, privacy zones, GPX, insights, recording state machine |
| `data` | Room database, settings (DataStore), crash-safe GPS journal, place names, backup |
| `recording` | Foreground location service, Bluetooth and companion-device triggers, recovery |
| `update`, `work`, `notify`, `widget` | In-app updater, background jobs, notifications, Glance widget |
| `ui` | Compose screens, theme, MapLibre map |

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug   # what CI runs
./gradlew assembleRelease -PversionName=1.2.0         # unsigned unless HEJJE_KEYSTORE_* are set
```

How a trip is stored: raw GPS fixes are kept only while recording. When a drive ends they are
turned into a simplified route plus a five-second profile (distance, moving time, top speed,
altitude), and the raw fixes are deleted. Every statistic is computed from that profile, so
trimming, splitting and merging stay exact.

Maps use [MapLibre](https://maplibre.org) with [OpenFreeMap](https://openfreemap.org) tiles
(OpenMapTiles schema). Map data and place names © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors. Place names come from Nominatim, at most one request every 1.5 seconds, cached on
the phone. No API keys are used anywhere.

### What the automated tests cover

- Processing, stats, simplification, merging, trimming, privacy zones, GPX, version comparison
  and the Bluetooth state machine, using simulated drives.
- The full recording path with simulated Bluetooth and GPS, including the app being killed mid-drive.
- Database edits with Undo, backup export and import, duplicate detection, journal recovery.
- The real app screens (trips, detail, insights, settings, onboarding) in light and dark mode.

### Known limitations

- Automatic start depends on Android's background rules. It needs "Allow all the time" location
  and either unrestricted battery use or the companion-device link.
- Hard braking is estimated from GPS speed once or twice a second, so very short events can be missed.
- GPS altitude is rough; elevation is much better on phones with a barometer.
- The map needs internet the first time an area is viewed. Without it the route is drawn on a plain background.
