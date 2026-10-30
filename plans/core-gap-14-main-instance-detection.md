# Main-instance detection by directory layout and normalized paths

Fixes known gap 14 of `docs/core-spec.md`.

## Problem

`MainProjectService.isRunningInMainInstance(repo, currentConfigDir)`
(`core/src/main/kotlin/com/projectjuggler/config/MainProjectService.kt:42-45`):

```kotlin
val baseConfigPath = ideConfigRepository.load().baseConfigPath ?: return true // assume main if not configured
return currentConfigDir.toString() == baseConfigPath
```

- Raw string comparison: `/x/config/` vs `/x/config`, or a symlinked `Application Support` path, read as "not main".
- When `baseConfigPath` is unset it returns true even when the caller is an isolated instance under
  `<ideDir>/projects/<id>/config`. The plugin calls this from `shouldPromptForMainProject` (`actions/Launch.kt:32`),
  `PluginSyncOnUpdateActivity.kt:31` and `StaleMainProjectCleanupActivity.kt:29`, so an isolated IDE with a fresh
  config could prompt for a main project or run main-only startup work.
- `plugin/.../services/IdePathSyncDetector.kt:30` already avoids the method for this reason and uses
  `currentConfigDir.startsWith(repository.baseDir)`; that rule belongs in core so both agree.

Decision made with the user: an isolated instance is recognized by its config dir living under `<ideDir>/projects/`;
otherwise compare normalized paths; keep "main when unset" only for non-isolated dirs.

## Target behavior

| `baseConfigPath` | `currentConfigDir` | Result |
|---|---|---|
| any | `<ideDir>/projects/<id>/config` (any depth under `<ideDir>/projects`) | false |
| unset | not under `<ideDir>/projects` | true |
| `/a/b` | `/a/b/.` or `/a/./b/` | true |
| `/a/b` | symlink resolving to `/a/b` (both exist) | true |
| `/a/b` | `/a/c` | false |

`shouldPromptForMainProject` inherits the change. `IdePathSyncDetector.detectMismatches` can later call the new helper;
that is a plugin change and is noted, not required, here.

## Implementation

`core/src/main/kotlin/com/projectjuggler/core/DirectoryManager.kt` (class, Koin-scoped):
- Add `fun isIsolatedConfigDir(dir: Path): Boolean`: `val projects =
  configRepository.baseDir.resolve("projects").toAbsolutePath().normalize()`;
  `dir.toAbsolutePath().normalize().startsWith(projects)`. Reuse the `"projects"` literal already used by
  `getProjectRoot`; extract `private val projectsDir = configRepository.baseDir.resolve("projects")` and use it in both.

`core/src/main/kotlin/com/projectjuggler/config/MainProjectService.kt` (object):
- `isRunningInMainInstance`:
  1. `if (DirectoryManager.getInstance(ideConfigRepository).isIsolatedConfigDir(currentConfigDir)) return false`.
  2. `val base = load().baseConfigPath ?: return true`.
  3. `return samePath(Paths.get(base), currentConfigDir)`.
- `private fun samePath(a: Path, b: Path): Boolean`: normalize both with `toAbsolutePath().normalize()`; if both exist,
  compare `toRealPath()` (catch `Throwable`, fall back to the normalized comparison).
- Update the KDoc.

`DirectoryManager.getInstance` requires a running Koin scope; all plugin call sites run after `KoinInit.init` (see
`IdeInstallationService`). Tests register `KoinTestExtension()`.

Related plans (independent): `plans/core-gap-09-running-detection.md` also edits `MainProjectService`/`DirectoryManager`
call sites in `ProjectLockUtils`; no dependency either way.

## Tests

Extend `core/src/test/kotlin/com/projectjuggler/config/MainProjectServiceTest.kt` (already uses `KoinTestExtension()`
and `createTempDir`):
- "isRunningInMainInstance is false for an isolated config dir": `repo.baseDir.resolve("projects/x-abc/config")`,
  `baseConfigPath` unset; false. Also with `baseConfigPath` set to that same dir; still false.
- "isRunningInMainInstance is true when base is unset and dir is not isolated".
- "isRunningInMainInstance normalizes paths": base `tempDir`, current `tempDir.resolve(".")` and
  `Paths.get(tempDir.toString() + "/")`; true.
- "isRunningInMainInstance follows symlinks": `Files.createSymbolicLink(link, tempDir)` (skip on Windows via
  `Platform.isWindows()` guard inside the test); current = link; true.
- "isRunningInMainInstance is false for a different dir".
- "shouldPromptForMainProject is false in an isolated instance even with no main project".

New `DirectoryManagerTest` case in `core/src/test/kotlin/com/projectjuggler/core/DirectoryManagerTest.kt`:
- "isIsolatedConfigDir recognizes project config dirs and rejects others" (also `baseDir` itself → false).

Manual check: `./gradlew :plugin:runIde` with an empty `~/.project-juggler-test`, open an isolated project; the isolated
IDE must not show the main-project prompt.

## Docs

`docs/core-spec.md` §7.2: replace the "Main instance" sentence with: a caller is in the main instance when its config
dir is not under `<ideDir>/projects/` and either `baseConfigPath` is unset or equals the dir after normalization (real
path when both exist). Delete item 14 from "Known gaps". Mention `DirectoryManager.isIsolatedConfigDir` in §4 or §7.2.

Optional follow-up noted in `docs/plugin-spec.md` only if that file describes `IdePathSyncDetector`'s own check: it can
now delegate to `DirectoryManager.isIsolatedConfigDir`.

## Sessions

1. Single session (Fable 5.1, default effort, ~10% context): read this plan, `CLAUDE.md`, `MainProjectService.kt`,
   `DirectoryManager.kt`, `MainProjectServiceTest.kt`, `DirectoryManagerTest.kt`. Implement, test, update spec, verify,
   finish.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.config.MainProjectServiceTest"`
2. `./gradlew :core:test --tests "com.projectjuggler.core.DirectoryManagerTest"`
3. `./gradlew :core:test`
4. `./gradlew :plugin:compileKotlin`
5. `./gradlew :sync-helper:build`
6. Manual step from Tests.

## Finishing

- Decisions live in spec §7.2; CLAUDE.md unchanged.
- Delete `plans/core-gap-14-main-instance-detection.md` in the same commit.
- `git status` / `git diff`; stage by path (two sources, two tests, spec, this plan). Commit in repo style, e.g. subject
  `detect isolated instances by config dir location in isRunningInMainInstance`, body with the why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Report anything left uncommitted.
