# ClimbNamer for Karoo

A [Karoo extension](https://github.com/hammerheadnav/karoo-ext) that gives names to the climbs
on the route you navigate on your Hammerhead Karoo. The Karoo shows climbs as
"Climb 2 of 5"; ClimbNamer tells you it's the *Kemmelberg*.

Companion of the [ClimbNamer browser extension](https://github.com/bertpinoy/ClimbNamer) for
the Hammerhead dashboard.

> **Not affiliated with Hammerhead or SRAM.** ClimbNamer is an independent project.

> **Status: early development (0.1.0).** Builds on GitHub; not yet tested on a Karoo.

## What it does

When you load a route (or navigate to a destination), ClimbNamer reads the climbs the Karoo
detected, works out where each climb is and looks up its name in OpenStreetMap. Names are
stored on the Karoo, so each climb is looked up only once and names work offline during the ride.

You decide where names appear. Everything can be switched on or off in the ClimbNamer app:

| | Where | Settings |
|---|---|---|
| 1 | **"Climb name" data field**: the current climb with distance to go, or the next climb | Add it to a ride page (or not); show the next climb when not climbing |
| 2 | **Alert** with the name when a climb starts | On/off; at the start or 250 m / 500 m / 1 km before; 5–30 s; also for unnamed climbs |
| 3 | **Map labels**: the name as a summit marker at the top of each climb | On/off |
| | **Looking up names** when a route is loaded | On/off; Nominatim fallback on/off; clear stored names |

The built-in Climber screens themselves can't be changed by extensions, so names appear in
these three places instead.

### How names are found

Same approach as the browser extension, using OpenStreetMap:

1. a named **summit or pass** near the top of the climb (e.g. *Kemmelberg*);
2. otherwise the **road** the climb mostly follows (in Flanders often the climb's name);
3. otherwise *Climb near &lt;village&gt;*.

Requests go through the Karoo's own connection (Wi-Fi or the companion app). Fair use of the
free OpenStreetMap services: one small Overpass query per climb, mirrors tried one after another,
Nominatim (optional) at most once per second, results cached, and every request identifies the
app as `ClimbNamer-Karoo/<version>`.

### Not possible (yet)

- **Pre-loading your latest routes at start-up.** The Karoo extension SDK only exposes the route
  that is being navigated, not the list of saved routes. Names are looked up as soon as a route is
  loaded, best done before the ride on Wi-Fi.
- **Changing the Climber screens** (see above).

## Install on a Karoo

1. Download `climbnamer.apk` from the [latest release](https://github.com/bertpinoy/ClimbNamer-Karoo/releases)
   (or the `climbnamer-apk` artifact of the latest [build](https://github.com/bertpinoy/ClimbNamer-Karoo/actions)).
2. Install it on the Karoo: share the download link with the Hammerhead companion app, or use
   `adb install climbnamer.apk` with the Karoo connected over USB (developer mode).
3. Open **ClimbNamer** on the Karoo to check the settings.
4. Add the **Climb name** field (ClimbNamer) to a ride page in your ride profile.

## Build

The app is built by GitHub Actions on every push ([build.yml](.github/workflows/build.yml)); tags
`v*` create a release with the APK and a `manifest.json` for Karoo update checks.

Building locally needs JDK 17 and the Android SDK, plus a GitHub token with `read:packages`,
because karoo-ext is published on GitHub Packages (which always requires a login):

```properties
# ~/.gradle/gradle.properties (never commit this)
gpr.user=<your GitHub user>
gpr.key=<token with read:packages>
```

```bash
./gradlew testDebugUnitTest assembleRelease
```

### Signing

Without signing secrets, release APKs are signed with a throw-away debug key, and an update only
installs over an APK signed with the same key. Before sharing builds, create a keystore and add
these repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

## Code

| Path | Contents |
|---|---|
| `extension/ClimbNamerExtension.kt` | The Karoo extension: route tracking, alerts, map labels |
| `extension/ClimbNameDataType.kt` | The "Climb name" data field (Glance view) |
| `route/RouteClimbs.kt` | Climb positions and summit from the route polyline and elevation profile |
| `naming/ClimbNamer.kt` | OpenStreetMap lookups (Overpass, Nominatim) |
| `naming/NameCache.kt` | Names stored on the Karoo |
| `settings/Settings.kt` | All on/off settings |
| `ui/MainActivity.kt` | Settings screen |

Map data © OpenStreetMap contributors (ODbL).
