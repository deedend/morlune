# Writing a weather provider

A provider is one JSON file. No code, no rebuild: import it in the app or drop it in this folder.
If a source is too awkward for this format, implement `WeatherProvider` in Kotlin instead.

## Skeleton

```json
{
  "schema": 1,
  "id": "my-provider",
  "name": "My Provider",
  "attribution": "Data from ...",
  "webUrl": "https://example.org/{geohash:6}",
  "requests": [ { "id": "main", "url": "https://example.org/api?lat={lat:3}&lon={lon:3}", "type": "json" } ],
  "current": { "temperature": "$.now.temp", "condition": { "path": "$.now.code", "map": "codes" } },
  "daily": {
    "layout": "rows", "items": "$.days[*]", "limit": 7,
    "fields": { "date": "$.date", "min": "$.lo", "max": "$.hi", "condition": { "path": "$.code", "map": "codes" } }
  },
  "conditionMaps": { "codes": { "CLEAR": ["0"], "RAIN": ["10-19"], "CLOUDY": ["~cloud"] } }
}
```

## Placeholders (URLs and headers)

`{lat}` `{lon}` `{lat:N}` `{lon:N}` (N decimals), `{geohash:N}`, `{name}`, `{tz}` (time zone, e.g. Australia/Melbourne),
`{extra.key}`, and `{today}`. URLs must be https.

## Fields

Current: `temperature`, `feelsLike`, `rain`, `condition`, `isDay`.
Daily: `date` (required), `min`, `max`, `condition`, `rain`, `rainChance`.
Temperatures end up in °C and rain in mm. `rain` in `current` means rain today (observed if the source has it, forecast total otherwise).

A field is either a shorthand string (a JSON path starting with `$`, or a CSS selector) or an object:

| key | meaning |
|---|---|
| `request` | which request to read (optional if there is only one) |
| `path` | JSON path: `$.a.b`, `$.a[0]`, `$.a[-1]`, `$.a[*]`, `$..key` |
| `selector`, `attr` | CSS selector for HTML; `attr` is an attribute name, default is the text |
| `regex` | pick part of the text; group 1 if present |
| `scale`, `offset` | `value * scale + offset`, applied before unit conversion |
| `unit` | temperature: `c` `f` `k`; rain: `mm` `cm` `in` |
| `negate` | flip a boolean (for example `is_night` into `isDay`) |
| `map` | condition map name |
| `dateFormat` | `iso-date` (default), `instant-local`, `epoch-s`, `epoch-ms`, `sequential` (today + row number), or a Java pattern like `EEE d MMM` |

Numbers are found in text automatically ("High 21°" gives 21), so `regex` is only needed for awkward text.

## Daily layouts

`rows`: `items` selects the rows (a JSON path or a CSS selector) and each field is read relative to its row.
`columns` (JSON only): each field is a path to an array, and index N across the arrays is day N, as Open-Meteo does.

## Condition maps

Keys are condition names (`CLEAR`, `PARTLY_CLOUDY`, `CLOUDY`, `FOG`, `HAZE`, `DUST`, `DRIZZLE`, `RAIN`, `HEAVY_RAIN`, `SHOWERS`, `THUNDERSTORM`, `HAIL`, `SNOW`, `FROST`, `WIND`).
Values are patterns: `"sunny"` exact (case-insensitive), `"51-57"` numeric range, `"~shower"` contains.
First matching entry wins, so put specific entries before general ones. Unmapped values give `UNKNOWN` and a warning.

See `app/src/main/assets/providers/bom-au.json`, `open-meteo.json` and `docs/examples/html-example.json`.
