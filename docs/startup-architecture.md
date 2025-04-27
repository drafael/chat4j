# Startup Architecture

This document describes how Chat4J boots from process start to first window render.

## Entry point

`src/main/java/com/github/drafael/chat4j/App.java` is intentionally minimal:

```java
public static void main(String[] args) {
    new ApplicationBootstrap().start();
}
```

All startup orchestration lives in `bootstrap/`.

## Bootstrap components

- `ApplicationBootstrap`
  - High-level startup coordinator.
  - Owns startup order and UI/database initialization.
- `EnvironmentBootstrapper`
  - Handles macOS jpackage shell-environment loading workflow.
  - Initializes `CredentialResolver` and computes warning behavior.
- `AppServices`
  - Record containing startup-created services needed by `MainFrame`.
- `EnvironmentInitResult`
  - Record with loaded shell env map and warning decision.

## Startup sequence

`ApplicationBootstrap.start()` runs in this order:

1. `configurePlatformIntegration()`
   - Sets macOS-specific properties before Swing/AWT usage.
2. `configureEarlyLookAndFeel()`
   - Installs FlatLaf and accent-color getter early.
3. `environmentBootstrapper.initialize()`
   - Loads shell env when needed and initializes `CredentialResolver`.
4. `initializeStorage()`
   - Creates paths/data source, runs Flyway bootstrap, creates repos.
5. `applySavedAppearance(...)`
   - Restores saved accent/theme/fonts.
6. `showMainWindow(...)`
   - Constructs and shows `MainFrame` on EDT.
7. `showEnvironmentWarningIfNeeded(...)`
   - Non-blocking warning for missing API-key env in macOS jpackage context.

## Why this structure

- Keeps `App.main()` tiny and readable.
- Makes startup order explicit and easy to extend.
- Isolates environment-specific behavior from general UI/storage startup.
- Makes core startup decisions testable (`EnvironmentBootstrapperTest`).

## Extension points

Add new startup concerns by inserting explicit steps in `ApplicationBootstrap.start()`, for example:

- single-instance lock
- startup diagnostics/telemetry
- update checks
- preloading provider/model metadata
- additional health checks

Prefer adding one clearly named private method per step to preserve readability.

## Related docs

- `docs/macos-jpackage-environment-variables.md`
