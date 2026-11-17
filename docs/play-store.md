# Hejje Guruthu – Google Play launch kit

Everything to paste into Play Console, and the steps only you can do. The app itself is ready for
Play: the GitHub updater is gone, every release builds a Play bundle (`hejje-<version>.aab` on the
GitHub release), and the privacy policy is published.

## 1. Store listing

**App name** (30 characters max): `Hejje Guruthu`

**Short description** (80 max):

> Records every drive on its own. Trips, stats and stops stay on your phone.

**Full description**:

> Hejje Guruthu records your drives by itself. Pair it with your car's Bluetooth once, and every
> trip is saved the moment you drive off: no buttons, no accounts.
>
> SEE EVERY DRIVE
> • Your route on the map, coloured by speed
> • Distance, time, average and top speed, time stuck in traffic
> • A timeline of each trip: where you stopped and for how long
> • Elevation, cornering, braking and a smoothness score
>
> MADE FOR REAL TRAFFIC
> • Measures crawling and stop-and-go traffic with your phone's motion sensors as well as GPS
> • A live dashboard while you drive: speedometer, compass with your route, trip odometer
>
> STOPS YOU REMEMBER
> • After parking, name the place: food, coffee, fuel or anything you like
> • Next time you drive that road, Hejje tells you the stop is coming up
>
> REPLAY YOUR DRIVE
> • Turn any trip into a short vertical video over a 3D landscape with real hills, the real sky of that hour
>   (night, sunrise, sunset) and the real weather, with your highlights
> • Made on your phone; share it or save it to your gallery
>
> ON ANDROID AUTO
> • Your saved stops on the car screen, the next one on your road first; tap to navigate
> • Driving time, trip distance and a compass with the road you've driven
>
> YOUR DATA STAYS YOURS
> • Trips are stored only on your phone
> • No account, no cloud, no ads, no tracking
> • Privacy zones hide your home and work on shared maps and exports
> • Export to GPX, back up to a folder you choose
>
> "Hejje" is Kannada for footprint.

**Category**: Auto & Vehicles. **Contains ads**: No. **In-app purchases**: No.

**Privacy policy URL**: <https://github.com/usr-canis/hejje/blob/HEAD/PRIVACY.md>

**Graphics** (in [`docs/store/`](store/); `StoreGraphicsTest` redraws the icon and feature graphic):
- App icon 512 × 512: `icon-512.png`
- Feature graphic 1024 × 500: `feature-1024x500.png`
- Phone screenshots 1078 × 2156 (2:1): `phone-1.png` to `phone-6.png`. Screenshots from a real
  phone look better (they show map tiles); any 9:16 to 1:2 portrait shot works.
- Android Auto screenshots, if Play asks for them: take them in the Desktop Head Unit or the car.

## 2. App content forms

**Target audience**: 18 and over (a driving app; this also keeps it out of the Families programme).

**Content rating** questionnaire: a utility app. No violence, sexual content, profanity, drugs,
gambling, user-to-user communication, or sharing of the user's location with other users. Expected
rating: Everyone / PEGI 3.

**Ads**: No ads.

**App access** (for reviewers): "All features are available without an account. Recording starts
automatically when the phone connects to the car chosen in Settings → Car. Without a car, start
and stop a recording with the Record button on the Trips screen or the Quick Settings tile."

**News app / COVID / Government / Financial features**: No.

## 3. Data safety

Everything the app stores stays on the phone (not "collected" in Play's sense). A few things leave
the phone, all over HTTPS:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data is deleted? | **Yes** (Settings → Delete all data; uninstalling removes everything) |

Data types:

| Type | Collected | Shared | Why it is declared | Optional? | Purpose |
|---|---|---|---|---|---|
| **Location → Precise location** | Yes | Yes | Trip start and end coordinates are sent to OpenStreetMap's Nominatim to show place names | Yes: Settings → "Look up place names" | App functionality |
| **Location → Approximate location** | Yes | Yes | Map tile requests (OpenFreeMap, and AWS terrain tiles for replay videos) reveal the area being viewed; replay videos send the trip's date and rough position (about 10 km) to Open-Meteo for its weather | No (replay videos only when the user makes one) | App functionality |

Not collected: personal info, financial info, health, messages, photos, audio, files, calendar,
contacts, app activity, web history, device IDs, diagnostics. No analytics or crash-reporting
code is included.

Ephemeral processing: the app does not keep the data off the phone. Answer "No" if Play asks
whether data is processed ephemerally, because the third-party services may keep server logs.

## 4. Permission declarations

**Location in the background** (Policy → App content → Sensitive permissions):

> Hejje Guruthu records drives automatically. When the phone connects to the user's car over
> Bluetooth, the app starts recording the trip's route in the background, and stops when the car
> disconnects, so the user never has to open the app. Location is used only while a drive is
> recorded; a notification is shown the whole time. Without background location, trips would be
> missed whenever the app is not on screen, which is nearly always while driving.

**In-app notice** (Play's "prominent disclosure"): the welcome guide's "Allow it all the time"
step shows this, right before Android's own prompt:

> This app collects location data to record your drives (route, distance, speed and where you
> park) even when the app is closed or not in use. Your trips stay on this phone and are never
> used for ads. To name places, start and end points are sent to OpenStreetMap; you can turn this
> off in Settings.

**Demo video** (needed, 30–60 s, unlisted YouTube link). Record on the phone:
1. Install fresh (or clear the app's storage) and open it. Go through the guide to the
   "Allow it all the time" step, show the notice, tap "Open location setting" and choose
   "Allow all the time".
2. Show Settings → Car with the car chosen and "Record automatically" on.
3. Close the app. Connect the phone to the car (start the engine); show the "Recording"
   notification appearing by itself.
4. Drive a short distance. Disconnect; show the trip saved in the app with its route.

**Foreground service type: location** (Policy → App content → Foreground service permissions):
choose **Location**, describe it as "Records the route of a drive while the user drives, started
automatically when the phone connects to the car, with an ongoing notification", and reuse the
same video.

Other permissions need no form: notifications, Bluetooth "nearby devices" (to see the car
connect), companion device (to link the car), boot (to resume after a restart).

## 5. Signing and your current install

Upload the `.aab` from the GitHub release. Play Console offers two ways to sign:

- **Let Google create the app signing key (simplest).** The key the GitHub builds use becomes the
  upload key. Your phone's current GitHub install cannot update from Play, so first make a backup
  (Settings → Back up), uninstall, install from Play, and restore the backup.
- **Use your existing key** ("Export and upload a key from Java keystore" with Google's PEPK tool
  and the keystore you keep for the GitHub builds). Play then updates your current install
  directly, keeping all trips. A little more setup.

Keep the keystore and its password safe either way: losing the upload key means asking Google to
reset it.

## 6. Android Auto

In Play Console: Release → Setup → Advanced settings → Form factors → add **Android Auto** and
accept the car app quality terms. The car app is a "points of interest" app: your saved stops,
with navigation to them. Google reviews car apps separately; if the car review asks for changes,
the phone app can go live meanwhile.

## 7. Release order

1. Internal testing track first (just you): upload the `.aab`, install from the test link, check a
   drive and Android Auto.
2. Then production. Google's first review usually takes a few days, longer for apps with
   background location.
