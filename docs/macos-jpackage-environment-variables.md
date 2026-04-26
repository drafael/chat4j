# macOS jpackage Environment Variables Workaround

## The Problem

When a Java desktop app is packaged with `jpackage` and installed as a `.app` bundle on macOS, it is launched by `launchd` — not from a terminal shell. This means:

- `System.getenv()` returns `null` for any environment variable set in the user's shell profile (`.zshrc`, `.bashrc`, `.zprofile`, etc.)
- API keys like `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, etc. are invisible to the app
- The same JAR works fine when run directly from terminal (`java -jar`) because it inherits the shell's environment

This is a **macOS-only** issue. Windows and Linux jpackage apps inherit system environment variables normally.

## Why This Happens

macOS launch chain for bundled apps:

```
Dock click → launchd → /Applications/Chat4J.app/Contents/MacOS/Chat4J
```

`launchd` provides a minimal environment with only system-level variables (`HOME`, `PATH`, `USER`, etc.). It does **not** source any shell profile files.

In contrast, terminal launch:

```
Terminal → zsh (sources .zshrc) → java -jar chat4j.jar
```

The shell sources `.zshrc`/`.zprofile` before running Java, so all `export`-ed variables are available.

### `open` command caveat

Running `open /Applications/Chat4J.app` from a terminal may appear to work because `open` can pass through some environment from the calling terminal session. This is **not** the same as launching from the dock and should not be used to verify the fix.

## The Solution

At startup, detect if the app is running as a jpackage bundle on macOS, and if so, spawn a login+interactive shell subprocess to capture the user's full environment.

### Architecture

```
App.main()
  └── ApplicationBootstrap.start()
        ├── configure platform + early look and feel
        ├── EnvironmentBootstrapper.initialize()
        │     ├── if jpackage + macOS: show splash + load shell env
        │     ├── ShellEnvironmentLoader.loadFromLoginShell()
        │     │     └── runs: /bin/zsh -l -i -c "env"
        │     │     └── parses output into Map<String, String>
        │     └── CredentialResolver.init(shellEnv)
        ├── initialize storage + UI
        └── optional warning if shell env missing and no provider keys available

All env lookups throughout the app:
  System.getenv(name)  →  CredentialResolver.getenv(name)
                            ├── System.getenv(name)     ← check JVM env first
                            └── shellEnv.get(name)      ← fallback to shell env
```

### Key Components

#### 1. `ShellEnvironmentLoader` (`com.github.drafael.chat4j.env`)

Runs the user's login shell as a subprocess and captures all environment variables.

```java
// Detects user's shell, falls back to /bin/zsh
String shell = System.getenv("SHELL");  // may be null under launchd
if (shell == null || shell.isBlank()) {
    shell = "/bin/zsh";
}

// Runs: /bin/zsh -l -i -c "env"
ProcessBuilder pb = new ProcessBuilder(shell, "-l", "-i", "-c", "env");
```

**Shell flags explained:**

| Flag | Meaning | Why needed |
|------|---------|------------|
| `-l` | Login shell | Sources `.zprofile`, `.zlogin` |
| `-i` | Interactive shell | Sources `.zshrc` — **where most users set API keys** |
| `-c "env"` | Run command | Prints all environment variables as `KEY=VALUE` lines |

**Critical:** Using only `-l` without `-i` was the initial implementation, and it **did not work** because `.zshrc` (where API keys are typically exported) is only sourced for interactive shells. The loader now tries `-l -i` first, then falls back to `-l` if interactive shell loading fails.

**Timeout:** 5 seconds by default (`-Dchat4j.shellEnvTimeoutSeconds=<n>` to override).
Typical execution is ~100-200ms. On timeout, the process is killed and the loader falls back to a login-only shell attempt.

**Parsing:** Each line is split on the first `=` character. Lines without `=` are skipped. Multi-line values are not supported (edge case, not relevant for API keys).

#### 2. `CredentialResolver` (`com.github.drafael.chat4j.provider.support`)

Centralized environment variable access with shell env fallback.

- `init(Map<String, String> env)` — called once at startup with shell env
- `getenv(String name)` — checks `System.getenv()` first, falls back to shell env
- `hasRequiredCredentials(String envVar)` — uses `getenv()` internally
- `resolveRequiredApiKey(String envVar, String fallback)` — uses `getenv()` internally

**All code that previously called `System.getenv()` for API keys must use `CredentialResolver.getenv()` instead.** This includes:

- `ProviderService.isAvailable()` — checks if a provider's API key is set
- `ProvidersPanel` — displays key status in settings dialog

#### 3. jpackage Detection

```java
System.getProperty("jpackage.app-path") != null && SystemInfo.isMacOS
```

- `jpackage.app-path` is a system property set automatically by the jpackage runtime — it is **not** present when running via `java -jar` or `mvn exec:java`
- `SystemInfo.isMacOS` is a FlatLaf utility — ensures we only run this on macOS

#### 4. Splash Dialog

A small modal `JDialog` shown during env loading:

- Created on the EDT via `SwingUtilities.invokeAndWait`
- Contains a label ("Loading environment...") and an indeterminate `JProgressBar`
- Non-resizable, centered on screen
- Dismissed after env loading completes
- The modal `setVisible(true)` call blocks until loading finishes, so env is initialized before provider usage

#### 5. Failure Warning

If `ShellEnvironmentLoader` returns an empty map **and no known provider API key is available via `System.getenv()`**, a `JOptionPane` warning is shown after the splash is dismissed:

- Title: "Environment Warning"
- Explains that API keys could not be loaded from the shell profile
- Suggests alternatives: run from terminal, use `launchctl setenv`, use Ollama, or run startup diagnostics (`chat4j-doctor`)
- Non-blocking — the app continues to launch (Ollama and any manually configured providers still work)

## Files

| File | Description |
|------|-------------|
| `src/main/java/.../bootstrap/ApplicationBootstrap.java` | Main startup orchestration |
| `src/main/java/.../bootstrap/EnvironmentBootstrapper.java` | macOS jpackage env init + warning decision |
| `src/main/java/.../env/ShellEnvironmentLoader.java` | Shell subprocess, env parsing |
| `src/main/java/.../provider/support/CredentialResolver.java` | Centralized env access with fallback |
| `src/main/java/.../provider/api/ProviderService.java` | Uses `CredentialResolver.getenv()` |
| `src/main/java/.../settings/ProvidersPanel.java` | Uses `CredentialResolver.getenv()` |

See also: [`docs/startup-architecture.md`](startup-architecture.md)

## Platform Behavior

| Platform | jpackage env vars | Shell loading needed |
|----------|-------------------|---------------------|
| **macOS** | Not inherited from shell profiles | **Yes** — this workaround |
| **Windows** | Inherited from System Environment Variables | No |
| **Linux** | Inherited from desktop session environment | No |

## Troubleshooting

### API keys still not detected after fix

1. Verify the key is exported in `.zshrc` (not just assigned):
   ```bash
   # Correct:
   export ANTHROPIC_API_KEY="sk-..."
   
   # Wrong (not exported — only available in the current shell, not subprocesses):
   ANTHROPIC_API_KEY="sk-..."
   ```

2. Verify the shell can produce the env:
   ```bash
   /bin/zsh -l -i -c "env" | grep ANTHROPIC_API_KEY
   ```

3. If the user's `.zshrc` has interactive-only guards that prevent loading in non-TTY contexts, the env loading may fail silently. Look for patterns like:
   ```bash
   [[ -o interactive ]] || return   # This blocks our subprocess
   ```

### Shell subprocess hangs

Some `.zshrc` configurations start interactive tools (e.g., `tmux`, `ssh-agent` prompts) that wait for input. The 5-second timeout kills the subprocess, and the app falls back to an empty env with a warning dialog.

### App does not open at all (Gatekeeper/signing/quarantine)

If the app fails before the JVM starts, in-app logs are not available. Run manual diagnostics:

```bash
bash "/Applications/Chat4J.app/Contents/app/classes/tools/chat4j-doctor.sh" --app "/Applications/Chat4J.app"
```

The report is written to:

- `~/.config/chat4j/logs/doctor/doctor-<timestamp>.md`

Use `--strict` to require identity metadata (`appleTeamId`, `appleBundleId`) in build metadata.

### Alternative: `launchctl setenv`

Users who cannot use the shell loading workaround can set env vars globally for all GUI apps:

```bash
launchctl setenv ANTHROPIC_API_KEY "sk-..."
```

This persists until logout. To make it permanent, add it to a launch agent plist.

## Startup diagnostics fallback log

If startup fails after the process begins, Chat4J writes emergency bootstrap diagnostics to:

- `~/.config/chat4j/logs/bootstrap-fallback.log`

This fallback log is written before SLF4J/Logback is fully initialized and includes a direct `chat4j-doctor` command hint.
