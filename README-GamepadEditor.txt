DroidDeck 0.6.9 - Gamepad-style ScreenKeys editor source patch

Base source: DroidDeck release tag 0.6.9 (commit cdde9d3390defe4ee0c8f8236b9ee7b00d288b78)

This is an overlay patch, not a complete APK or complete source tree.
Extract this ZIP into the root of a clean DroidDeck 0.6.9 source checkout and allow the files to overwrite.

Changes:
- ScreenKeys picker now offers virtual gamepad buttons, D-pad directions, and analog-stick controls.
- ScreenKeys routes those actions into PadBridge instead of sending them as guest keyboard events.
- Analog stick touch coordinates produce continuous normalized X/Y axis values.
- Per-game screen layouts are stored under a profile key derived from SessionState.gameId, falling back to SessionState.program.
- Existing keyboard bindings and named layout import/export remain available.

Build from the project root on a machine with Android SDK platform 34, a compatible JDK, and network access for Gradle dependencies:
  ./gradlew :app:assembleDebug

Important verification status:
- The new GamepadTouchInput Kotlin file passed a standalone Kotlin compile and a small mapping smoke test.
- A full Android Gradle build was NOT completed in the authoring environment: the Gradle wrapper could not download Gradle because network DNS access was unavailable, and no Android SDK was installed.
- This patch has not yet been tested on an Android device. Do not treat it as a verified release APK.
