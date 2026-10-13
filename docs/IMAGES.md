# Weather pictures and icons

## Background pictures

The widget shows `res/drawable-nodpi/bg_<key>.webp`, where `<key>` is one of:

`clear`, `partly_cloudy`, `cloudy`, `fog`, `rain`, `heavy_rain`, `storm`, `snow`, each with `_day` and `_night`
(16 files). Which key is used comes from `Backgrounds.key()` in the engine.

The pictures in the repo are **generated placeholders** (gradients, noise, streaks). To use real photos:

1. Use only images you may redistribute under GPL-compatible terms: your own photos, or CC0 / public domain.
   Unsplash, Pexels and Pixabay licences are not clearly GPL-compatible, so avoid them for bundled images.
2. Crop to about 2:1, resize to roughly 800x400 and export as WebP at quality 70 or so (60 to 120 KB each).
3. Replace the file with the same name. Keep night pictures dark enough for white text.
4. Record the author and licence of each picture in `docs/IMAGE_CREDITS.md`.

## Icons

Icons are vector drawables named `wi_<set>_<name>` (for example `wi_simple_rain`). To add a set, add the same
11 names under a new set name and list it in `res/values/arrays.xml` (`icon_pack_*`). Icons are tinted by the
"Icon colour" setting, so draw them in one colour.

Names: clear_day, clear_night, partly_day, partly_night, cloudy, fog, rain, showers, storm, snow, wind.
The "simple" set is original work released under the same licence as the app.

## Your own pictures (like Weawow)

This is optional and off by default (the built-in pictures are used). Pick a folder under **Choose my picture folder…**
(global settings), then switch on **Use my own pictures** in the settings of each widget that should use them.
Where no picture matches, the built-in one is shown. Morlune copies shrunk versions into its own
storage (up to 150 pictures, longest edge 800 px) and shows one that matches the current weather. A different one
is picked at each weather update. Remove them again with **Remove my pictures**.

Pictures are sorted by the words in the folder names and file names, so no setup file is needed:

| Weather | Words that match |
|---|---|
| clear | clear, sunny, sunshine, sun, bluesky |
| partly cloudy | partly, scattered, broken |
| cloudy | cloud, cloudy, overcast |
| fog | fog, mist, haze, hazy, smog |
| rain | rain, shower, drizzle |
| heavy rain | heavy or downpour or torrential, together with a rain word |
| storm | thunder, storm, lightning |
| snow | snow, sleet, blizzard |

Add `night` (also evening, dusk, moon, star) or `day` (also morning, noon) to the name for a time of day. Without
one, the picture is used for both. Examples: `rain/heavy_night_3.jpg`, `Clear sky - morning.jpg`, `snow/IMG_1.jpg`.
Pictures with no weather word are skipped, and the app tells you how many.

Weawow's own photos are sold under paid licences and cannot be redistributed, so they are not bundled. Pictures you
use privately on your own phone are your business; just do not publish them in this repository.
