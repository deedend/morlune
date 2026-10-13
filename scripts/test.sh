#!/usr/bin/env bash
# Compiles the engine + tests with kotlinc and runs them (no Gradle needed). Needs kotlinc and jsoup.jar.
set -euo pipefail
cd "$(dirname "$0")/.."
JSOUP="${JSOUP:-/usr/share/java/jsoup.jar}"
OUT="${OUT:-/tmp/morlune-build}"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc -nowarn -cp "$JSOUP" -d "$OUT" engine/src/main/kotlin engine/src/test/kotlin
java -Dproviders.dir=app/src/main/assets/providers -Dexamples.dir=docs/examples -cp "$OUT:$JSOUP:$(dirname "$(which kotlinc)")/../lib/kotlin-stdlib.jar" org.morlune.engine.EngineTestsKt
