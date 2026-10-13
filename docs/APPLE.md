# Apple WeatherKit

Apple's forecast API (the data behind the iOS Weather app) works with Morlune, but only with **your own Apple
developer account**. Apple does not offer it to anyone else, and it is not something this project can provide.
I could not test this against Apple's servers, so treat it as experimental and tell me what the status line says.

## What you need

- An Apple Developer Program membership (paid, yearly). Apple's terms, prices and free monthly quota change, so
  check their current WeatherKit pages.
- In your Apple developer account (Certificates, Identifiers & Profiles):
  1. An identifier (an App ID, or a Services ID) with **WeatherKit** switched on. Its reverse-domain name, for
     example `com.example.morlune`, is the **Service ID** in Morlune.
  2. A **Key** with WeatherKit enabled. Download the `AuthKey_XXXXXXXXXX.p8` file once (Apple only lets you do it
     once). The 10 characters in the file name are the **Key ID**.
  3. Your **Team ID** (10 characters, shown in the account's Membership details).

## In Morlune

Global settings, **Apple WeatherKit**: type the Team ID, Service ID and Key ID, then choose the `.p8` file.
Then pick **Apple WeatherKit** as the weather provider. Morlune signs a short-lived token on the phone for each
fetch; the key stays in the app's private storage and is not included in backups.

If it fails, the widget shows the reason, and the Open-Meteo fallback (if switched on) keeps the widget updated.

## Attribution

Apple requires apps that show its data to display the Apple Weather mark and link to
https://weatherkit.apple.com/legal-attribution.html. Morlune shows the credit and the link in **Weather data
credit** in the global settings. Check Apple's current terms to see whether that is enough for how you use it.
