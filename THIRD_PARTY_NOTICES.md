# Third-party notices

RoutinePilot is released under the MIT License (see `LICENSE`). It uses third-party software, and each component stays under its own license. This file records what was checked. It is not a complete notice bundle.

## What was checked

- **Android app runtime.** The release runtime classpath (`:app:dependencies --configuration releaseRuntimeClasspath`) resolves to 267 coordinates. Each artifact's license was read from its resolved POM. The declared licenses are Apache-2.0 (the large majority, including AndroidX, Jetpack Compose, Kotlin, kotlinx, Room, Hilt, and Dagger), plus a few BSD and MIT entries. No copyleft license was found among the shipped artifacts.
- **Backend runtime.** The runtime classpath (`dependencies --configuration runtimeClasspath`) resolves to 80 coordinates: Ktor 3.0.3, Netty, Kotlin, kotlinx, HikariCP (Apache-2.0 per its POM), Typesafe Config 1.4.3 (Apache-2.0 per its POM), the PostgreSQL JDBC driver 42.7.4 (BSD-2-Clause per its POM), logback 1.5.12 (see below), and SLF4J (MIT per the `slf4j-parent` POM, which the backend's SLF4J versions inherit; the parent read was 2.0.7).

## Items to review before publication

- **logback 1.5.12** (`logback-classic` and `logback-core`) declares two licenses on its `logback-parent` POM: the Eclipse Public License 1.0 and the GNU Lesser General Public License. The POM does not say which one applies, so a recipient may choose between them. The backend image ships logback unchanged. Keep logback's license texts with the image, and review this choice before publishing a container image.
- **Gradle wrapper** (`gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, and the backend copies). These are Gradle's own files under Apache-2.0. The wrapper jar carries its `META-INF/LICENSE`. Keep both when the files are copied.
- **Build-time tools** (Kotlin compiler, detekt, Spotless, ktlint, the Android Gradle Plugin, Room compiler, and JAXB). They are not shipped in the app or the backend image, so their licenses do not attach to the published artifacts. Some (such as the Room compiler and JAXB) carry BSD licenses.

## What was not checked

- The license files inside each shipped artifact (`META-INF/LICENSE` and similar) were not collected or bundled.
- License compatibility was checked only from the POM metadata in the local Gradle cache. A legal review was not done.
- The Android release APK was not built with R8 minification, so no minification-dependent code changes were checked.
- No notice text is bundled in the APK or the backend image in this version. Before a store release, generate the full notice set from the release artifacts (for example, with the `licensee` or `com.github.jk1.dependency-license-report` Gradle plugins). Adding either plugin changes the build, so it is a roadmap item rather than part of this change.
