# ClimbNamer for Karoo

[![Build](https://img.shields.io/github/actions/workflow/status/bertpinoy/ClimbNamer-Karoo/build.yml)](https://github.com/bertpinoy/ClimbNamer-Karoo/actions/workflows/build.yml)
[![Downloads](https://img.shields.io/github/downloads/bertpinoy/ClimbNamer-Karoo/climbnamer.apk)](https://github.com/bertpinoy/ClimbNamer-Karoo/releases)
[![License](https://img.shields.io/github/license/bertpinoy/ClimbNamer-Karoo)](https://github.com/bertpinoy/ClimbNamer-Karoo/blob/main/LICENSE)

A [Karoo extension](https://github.com/hammerheadnav/karoo-ext) that gives names to the climbs
on the route you navigate on your Hammerhead Karoo. The Karoo shows climbs as
"Climb 2 of 5"; ClimbNamer tells you it's the *Kemmelberg*.

Companion of the [ClimbNamer browser extension](https://github.com/bertpinoy/ClimbNamer) for
the Hammerhead dashboard.

> **Not affiliated with Hammerhead or SRAM.** ClimbNamer is an independent project.

> **Status: early development.** Tested on a Karoo; see the [releases](https://github.com/bertpinoy/ClimbNamer-Karoo/releases).

## What it does

When you load a route (or navigate to a destination), ClimbNamer reads the climbs the Karoo
detected, works out where each climb is and looks up its name in OpenStreetMap. Names are
stored on the Karoo, so each climb is looked up only once and names work offline during the ride.

You decide where names appear. Everything can be switched on or off in the ClimbNamer app:

| | Where | Settings |
|---|---|---|
| 1 | **"Climb name" data field**: "Climbing · climb 1/2" with distance to the top, "Next climb in 3.4 km", or "All climbs finished" | Add it to a ride page (or not); show the next climb when not climbing |
| 2 | **Alert** with the name when a climb starts | On/off; at the start or 250 m / 500 m / 1 km before; 5, 8 or 15 s; also for unnamed climbs |
| 3 | **Map labels**: the name as a summit marker at the top of each climb | On/off |
| | **Looking up names** when a route is loaded | On/off; Nominatim fallback on/off; clear stored names |
| | **Progress in the Control Center**: "Naming climbs… 1 of 2", "2 climbs named", waiting for a connection | On/off; only shown when names had to be looked up |

The built-in Climber screens themselves can't be changed by extensions, so names appear in
these three places instead.

### How names are found

Same approach as the browser extension, using OpenStreetMap:

1. a named **summit or pass** near the top of the climb (e.g. *Kemmelberg*);
2. otherwise the **road** the climb mostly follows (in Flanders often the climb's name);
3. otherwise *Climb near &lt;village&gt;*.

Names are found in this order, fastest first:

1. the **built-in list of named hills and passes** in Belgium, the Netherlands and Luxembourg
   (1,149 places from OpenStreetMap): instant, without any connection;
2. **Nominatim**: a few seconds per climb;
3. **Overpass**: only when Nominatim fails (it is often slow or busy).

Requests go through the Karoo's own connection: **Wi-Fi, or the companion app on your phone over
Bluetooth**. The Karoo often switches Wi-Fi off once a route is loaded; with the companion app
connected, names are still looked up in the background as you ride. If there is no connection,
ClimbNamer retries after 1, 2 and 5 minutes and then every 10 minutes while the route is active;
"Retry now" in the app tries right away.

### During the ride

ClimbNamer keeps the climbs it received when the route was loaded, with fixed numbers (the Karoo
drops climbs from its own list once they're behind you). Your position comes from GPS matched to
the route. If you leave the route (a detour), the last position is kept until you're back on it;
climbs you skipped count as passed once you're beyond them. Climbs on a detour itself aren't named.

### Activity log

The app's "Activity" section lists what ClimbNamer did and when: route loaded, names looked up or
not (with the reason), alerts and Control Center updates. Handy to understand a ride afterwards.

Fair use of the free OpenStreetMap services: Nominatim at most once per second (3–4 requests per
climb), Overpass only as a backup with mirrors tried one after another, results stored on the
Karoo, spaced retries, and every request identifies the app as `ClimbNamer-Karoo/<version>`.

### Navigating to a route from elsewhere

When you start navigation away from the route (e.g. at home), the Karoo first guides you to the
nearest part of the route and then follows the route from there to its end. While you're on your
way, the Karoo only lists the climbs on that approach, not the ones on the route. ClimbNamer
therefore uses the Karoo's climbs for the approach (shown as "on the way to the start") and
**detects the climbs on the route part you'll ride itself**, from the route's elevation profile,
so all climbs are known and named as soon as the route is loaded. The detection is tuned against
Hammerhead's own: it finds the same climbs, within about 100–200 m, though it can occasionally
split a long climb in two. Climbs on the part of the route before the point where you join it
aren't ridden, so they aren't included.

The "Current route" section in the app shows each climb's position and a diagnostics line: route
length, approach length, where you join the route, and whether the elevation profile could be used.

### Not possible (yet)

- **Pre-loading your latest routes at start-up.** The Karoo extension SDK only exposes the route
  that is being navigated, not the list of saved routes. Names are looked up as soon as a route is
  loaded, over Wi-Fi or the companion app.
- **Changing the Climber screens** (see above).

## Install on a Karoo

**Via the Extension Library** (recommended): add this application manifest URL as a new extension
in your Hammerhead settings. ClimbNamer then appears in the Extension Library on your Karoo and
receives updates from new releases:

```
https://github.com/bertpinoy/ClimbNamer-Karoo/releases/latest/download/manifest.json
```

**Manually:**

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

## License

Copyright 2026 bertpinoy. Licensed under the [Apache License 2.0](LICENSE).
The Gradle wrapper files come from [karoo-ext](https://github.com/hammerheadnav/karoo-ext) (Apache-2.0).

Map data © OpenStreetMap contributors (ODbL); see [DATA-LICENSE.md](DATA-LICENSE.md).
