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
