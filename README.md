# Morlune

Morlune is a free, open-source Android home-screen widget combining a clock, weather forecasts and calendar events in a customisable daily dashboard.

**GPL-3.0-or-later · Android 8.0+ · No ads, accounts, tracking or Google Play Services required**

## Features

- **Clock and date:** large, adjustable clock; 12/24-hour format; separate date font size and date format.
- **Weather:** current temperature, today's minimum/maximum and rainfall, plus a configurable multi-day forecast with adjustable icons.
- **Calendar:** display events from your Android calendars in an agenda or week view. Choose 3–7 visible days, adjust today's column from 100% (equal widths) to 200% (twice the width of another day), and configure event density.
- **Appearance:** text and icon colours, background colour and transparency, weather-themed background images, optional pictures from your own folder, and independent image opacity.
- **Tap actions:** choose which clock, calendar and weather apps open when their respective widget areas are tapped, or open a weather web page.
- **Multiple widgets:** each widget can have its own appearance and app shortcuts; weather-provider and location settings are shared.
- **Background updates:** periodic weather refresh with cached data for offline use.

The widget initially occupies approximately **5 × 2 launcher cells**. The actual size depends on your home-screen launcher and can be adjusted after placing it.

## Getting started

1. Install Morlune and add its widget to your Android home screen.
2. Open **Morlune Settings → Weather provider** and select your weather source and location.
3. Allow calendar access if you want your events displayed. Grant location permission if you want Morlune to use the phone's last known location.
4. Under **Apps**, choose your calendar view and optional apps to open when tapping the widget.
5. Under **Theme**, adjust the clock, icons, colours and background images.

### Settings

| Section | Options |
| --- | --- |
| **Weather provider** | Weather sources, Open-Meteo fallback, update frequency, coordinates, detected suburb and optional place-name override |
| **Apps** | Clock/calendar/weather tap actions, agenda or week view, visible days, today-column width and forecast days |
| **Theme** | Clock and date sizes, icon size and style, colours, background transparency and weather pictures |

A blank *Place name* uses the detected suburb when Android geocoding can resolve one. Setting a place name overrides the displayed suburb without changing the forecast coordinates.

## Weather providers

- **Bureau of Meteorology (BOM)** (`bom-au`): default provider for Australia. It uses endpoints underlying the BOM website; these are not a stable, documented public API and may change. Keep polling intervals reasonable.
- **Open-Meteo** (`open-meteo`): worldwide forecasts, no API key required; can be enabled as fallback if the selected provider fails.
- **Custom providers:** import a provider JSON file. See [Provider documentation](docs/PROVIDERS.md) for the supported format.

Apple WeatherKit is **not included**.

## Location and privacy

- Morlune does not contain an advertising or analytics SDK and does not require an account.
- The selected weather provider receives the coordinates required to obtain a forecast. A configured fallback provider may also receive them if the primary provider fails.
- Calendar events are read locally on the device and are not sent to weather providers.
- With location permission, weather refreshes may check a recent last-known device location and update saved coordinates. This does not continuously track location or request an active GPS fix for every refresh. If Android cannot provide a suitable location, the last saved coordinates remain in use.
- Suburb lookup uses Android's geocoding service, whose availability and network behaviour depend on the device and its configured service.
- If you import a custom provider, its configured endpoints will receive the requests you authorise; review provider definitions before importing them.

## Build from source

Requirements: **JDK 17**, Android SDK (compile SDK 35) and Gradle wrapper.

```bash
./gradlew :engine:runTests
./gradlew :app:assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

```bash
./gradlew :app:assembleRelease
```

The release variant may require signing before it can be installed or distributed. The minimum supported Android API level is 26 (Android 8.0).

For standalone engine testing without Gradle, see `scripts/test.sh` (requires a Kotlin compiler and jsoup JAR).

## Source layout

- `engine/`: Kotlin weather-provider processing, calendar/forecast logic and unit tests.
- `app/`: Android widget, settings, rendering, permissions and WorkManager updates.
- `app/src/main/assets/providers/`: bundled weather-provider definitions.
- `docs/PROVIDERS.md`: custom weather provider format.
- `docs/IMAGES.md`: custom weather background images.

## Contributions and issues

Report bugs or propose changes in the [GitHub issue tracker](https://github.com/deedend/morlune/issues). Pull requests are welcome. For bug reports, include the Android version, launcher, app version and steps to reproduce the problem.

## Licence

Morlune is licensed under **GPL-3.0-or-later**. See [LICENSE](LICENSE).
