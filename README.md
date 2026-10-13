# Morlune

A no-fuss Android home-screen widget: big clock, date, place, current weather, a forecast strip and a calendar
snippet, with a background picture that matches the weather. Free software, GPL-3.0-or-later, no Google Play
Services, no accounts, no tracking.

## What it does

- Top row: big clock (TextClock, so it ticks without waking the app), date, place, current temperature, today's
  min/max and rain.
- Forecast strip: icon plus min/max for the coming days.
- Calendar: reads the system calendar (colours as in your calendar app), as an agenda list or a 7-day week.
- Weather background picture, opacity settable. Changes when new weather is fetched.
- Taps: clock opens an app you choose, the calendar opens the calendar app you choose, the weather opens a web page
  you choose (default: the provider's page).
- Settings per widget: clock style and size, date format, colours, background opacity, icon colour and set, forecast
  days, agenda rows, week start, events per day.
- Global settings: weather provider, location, update interval (1h, 2h, 6h, 12h, once a day).

## Weather providers

Providers are JSON files, no code needed: see [docs/PROVIDERS.md](docs/PROVIDERS.md). Included:

- `bom-au`: Australia's Bureau of Meteorology, using the JSON endpoints behind weather.bom.gov.au. These are
  undocumented and can change; BOM's terms restrict heavy automated use, so keep the update interval sensible.
- `open-meteo`: worldwide, documented, no key. Used as the automatic fallback.

Import your own from the settings, or add it to `app/src/main/assets/providers/` and send a pull request.
For sources that cannot be described in JSON, implement `WeatherProvider` in Kotlin.

## Building

```
./gradlew :engine:runTests     # provider engine + calendar logic tests (plain JVM)
./gradlew :app:assembleDebug   # APK in app/build/outputs/apk/debug
```

Needs JDK 17 and the Android SDK (compileSdk 35). minSdk is 26.
Without Gradle, `scripts/test.sh` runs the engine tests with just `kotlinc` and `jsoup.jar`.

## Layout

- `engine/`: pure Kotlin/JVM. Provider loader and scraper, JSON/JSONPath, calendar grouping, formatting, cache format.
  Everything here has tests.
- `app/`: the Android widget, settings screen and background updates (WorkManager).
- `app/src/main/res/drawable-nodpi/bg_*.webp`: **placeholder** weather pictures, generated gradients.
  See `docs/IMAGES.md` for how to replace them with photos.
- `docs/`: provider format and image notes.

## Privacy

The app only talks to the weather provider you choose (your coordinates are part of that request). Location is
typed in, or copied once from the phone's last known location; there is no background location access. Calendar data
is read on the phone and never leaves it.

## Licence

GPL-3.0-or-later. See `LICENSE`. Every source file carries an SPDX header.
