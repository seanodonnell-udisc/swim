# Swim

Swim draws your issue tracker as a dependency graph. The graph shows what blocks what. The graph also shows what is ready to start.

Swim is a Kotlin Multiplatform project. One core library serves a Compose Multiplatform app. The same library serves a command-line tool.

## Status

Swim is in early development. The author uses Swim every day. No other person has run it yet.

You must register your own OAuth applications before you sign in. Task 0004 gives the steps.

The desktop app is the only complete surface. The Android app and the iOS app build, but they show one screen.

`docs/demo.md` shows how to try the pull-request features with sample data and no GitHub account.

## What Swim does today

- Swim lays out issues as tidy blocker trees.
- Swim routes each connector around the cards.
- Swim groups issues by Swim group, team, project, or label.
- You can edit a relation from the graph.
- You can change an assignee, a status, a priority, or an estimate.
- Swim reads pull request stacks from GitHub. Swim derives blocking relations from those stacks.
- The command-line tool has 15 commands. It prints JSON for agents. It returns stable exit codes.

## Build

You need JDK 17 or later. Gradle downloads everything else.

```
./gradlew :desktopApp:run          # the desktop app
./gradlew :playground:hotRun       # the layout playground
./gradlew :cli:linkDebugExecutableMacosArm64
./gradlew :core:jvmTest :layout:jvmTest :shared:jvmTest
```

Build the iOS app with Xcode. Open `iosApp/iosApp.xcodeproj`. Use Android Studio for the Android app.

## Design

- `docs/spec.md` describes the product.
- `docs/design/providers.md` describes support for other trackers.
- `docs/security.md` describes how Swim stores your credentials.

## License

Swim uses the MIT license. See `LICENSE`.
