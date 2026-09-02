# Swim

## The contract

The `:core` module does not depend on Compose. The `:layout` module does not depend on Compose.
The `:layout` module has no dependencies.
Graph keys are Linear identifiers, for example `ENG-123`. Graph keys are not UUIDs.

## Build commands

Run the desktop app: `./gradlew :desktopApp:run`.
Run the unit tests: `./gradlew :core:jvmTest :layout:jvmTest :shared:jvmTest`.
Install the Android app on a connected device: `./gradlew :androidApp:installDebug`.
Build iOS with Xcode. Do not run the framework task by hand.

## More information

Read `docs/spec.md` for the product spec.
Read `docs/tasks/README.md` for the task tracker.
