// SPDX-License-Identifier: GPL-3.0-or-later
// NOTE: not yet run through Gradle (the sandbox could not reach Maven). scripts/test.sh is what was actually run.
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
}

kotlin { jvmToolchain(17) }

// Tests are a plain main() for now; run with: ./gradlew :engine:runTests
tasks.register<JavaExec>("runTests") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.morlune.engine.EngineTestsKt")
    systemProperty("providers.dir", rootProject.file("app/src/main/assets/providers").path)
    systemProperty("examples.dir", rootProject.file("docs/examples").path)
}
