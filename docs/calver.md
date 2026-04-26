# CalVer Documentation (Current State)

This document describes the **current CalVer implementation** in this repository.

## Version format

Chat4J uses CalVer in this format:

- `YY.M.N` (example: `26.4.0`)

Where:
- `YY` = 2-digit year
- `M` = month (`1-12`, non-zero-padded)
- `N` = monthly counter (`0+`)

Counter behavior:
- resets on month change
- starts at `0` for the first commit in a new month

## Source of truth

### `pom.xml`
- Maven project version (`<version>`) is the canonical application version.
- Current seeded version is `26.4.0`.

### `.buildnumber`
- Stores month and counter state used by the pre-commit updater.
- Format:

```properties
month=26.4
count=0
```

## Automatic version bumping

Version bumping is performed by a Git pre-commit hook.

### Hook files
- `.githooks/pre-commit` (wrapper)
- `scripts/calver-precommit.sh` (implementation)

### Hook logic
On each commit, the hook:
1. Reads current month as `YY.M`
2. Reads `.buildnumber`
3. Increments `count` if month matches, otherwise resets to `0`
4. Builds version `YY.M.N`
5. Updates Maven version via:
   - `org.codehaus.mojo:versions-maven-plugin:2.17.1:set`
6. Writes updated `.buildnumber`
7. Stages `pom.xml` and `.buildnumber`

## Hook installation

Hooks are repository-managed via `core.hooksPath`.

One-time setup per clone:

```bash
git config core.hooksPath .githooks
```

## Packaging version usage

All jpackage profiles use `${project.version}` for `--app-version`:
- `jpackage-mac`
- `jpackage-win`
- `jpackage-linux`

So installer version and Maven version are aligned.

## Build-time version validation

`pom.xml` includes `maven-enforcer-plugin` validation in `validate` phase.

Current regex rule for `project.version`:

- `^[0-9]{2}[.][1-9][0-9]?[.][0-9]+$`

This enforces `YY.M.N` with month range `1-99` (project usage is `1-12`).

## Operational notes

- Bypassing hooks (`git commit --no-verify`) can leave stale/invalid versions and may break CI/build expectations.
- The hook aborts commit if Maven version update fails.
- Version changes occur on every commit by design.
