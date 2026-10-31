# Key the current IDE on its real executable and stop splitting state across ideDirs (plugin-spec gap 10)

## Context

`docs/plugin-spec.md`, Known gaps 10: "`detectCurrentInstallation` falls back to an `IdeInstallation` keyed on the home
directory when the executable is missing, which produces a different `directoryName` than an installation found by
`IntelliJLocator`, splitting state across two `<ideDir>`s."

**Depends on `plans/plugin-tests.md` landing first** and is written against its layout. Its
`services/IdeInstallationServiceTest` pins this gap ("`currentInstallation.executablePath == PathManager.getHomePath()`
... fallback pinned, gap 10"); this plan flips it. Independent of `plans/plugin-gap-09-ide-path-drift.md`.

What the code does (`plugin/.../services/IdeInstallationService.kt`): `getIntelliJExecutablePath()` hardcodes the
script name `idea` (`MacOS/idea`, `bin/idea.sh`, `bin/idea64.exe` under `PathManager.getHomePath()`). The plugin
depends only on `com.intellij.modules.platform`, so in PyCharm, Android Studio, etc. the file is `MacOS/pycharm`,
`bin/studio.sh`, ... and the fallback `IdeInstallation(homePath, extractDisplayName(homePath))` is used. That
installation is not launchable (`IntelliJLauncher` runs `executablePath`, a directory), and its `<ideDir>` differs from
the one any executable-keyed caller (the locator, a later fixed plugin, `sync-helper --ide <exe>`) resolves.

Layouts checked against `IntelliJLocator.prepareScanConfig`/`scanToolboxDirectory` and `IntelliJLocatorTest`: macOS
standard and Toolbox `.../<X>.app/Contents/MacOS/idea` (home is `.../Contents`), Linux `/opt/<X>/bin/idea.sh`, Toolbox
`apps/<type>/ch-0/<ver>/bin/idea.sh`, Snap `.../current/bin/idea.sh`, Windows `<X>\bin\idea64.exe`. On this machine
the runIde sandbox home is the Gradle-transformed `ideaIU-2025.1.1-aarch64/` with `MacOS/idea` present
(`~/.project-juggler-test/v2/idea-b4214b81/config.json`). So `<home>/<MacOS|bin>/<script>[.sh|64.exe]` matches every
layout the locator knows; only the script name has to come from the running product.

A second split, found while reading: `IdeInstallation.directoryName` is `<sanitized displayName>-<id8>`, so the same
executable under two display names gets two dirs. `sync-helper` builds `IdeInstallation(idePath, "IDE")`
(`SyncHelperMain.kt:41`), i.e. `<base>/v2/IDE-<id8>`, never the plugin's `IntelliJ-IDEA-...-<id8>`; it then fails
with "Project not found", so self-shutdown sync does not work today. `docs/sync-helper-spec.md` gap 10 claims the
placeholder is "correct for directory resolution"; it is not.

Decision (made with the user, 2026-10-30): adopt an already-split ideDir once — if no dir exists for the new
installation and exactly one registered dir has `installation.executablePath == PathManager.getHomePath()`, rename it
to the new `directoryName` and rewrite its `installation` (skipped when an isolated IDE runs from it or the target
exists), because recents, main project and settings survive at the cost of one rename. Rejected: no migration
(orphans that state); merging both dirs (complex, conflicting `config.json`s).

Decision (made with the user, 2026-10-30): fix `sync-helper`'s split here, in core — `IdeRegistry.getRepository`
reuses an existing registered dir with the same `executablePath` whatever its display name — because it repairs
self-sync with no `sync-helper` code change and also stops display-name drift from splitting state. Rejected: leaving
it to a sync-helper plan (e.g. `--ide-dir`), which keeps self-sync broken until then.

## Target behavior

| Running IDE | Today `executablePath` | After |
|---|---|---|
| IntelliJ IDEA, exe present | `<home>/MacOS/idea` etc. | same |
| PyCharm (macOS) | `<home>` (fallback) | `<home>/MacOS/pycharm` |
| Android Studio (Linux) | `<home>` | `<home>/bin/studio.sh` |
| exe missing at the expected path | `<home>` | expected path anyway, warning logged |

| `getRepository(IdeInstallation(exe, name))` | Today | After |
|---|---|---|
| `v2/<name>-<id8>` exists | that dir | that dir |
| only `v2/<other name>-<id8>` with the same `executablePath` | new `v2/<name>-<id8>` | that dir, stored installation |
| neither | new dir (created on first write) | same |

Legacy adoption (see Decision above): `v2/<home name>-<hash(home)>` with `executablePath == home` and no
`v2/<new dir>` -> renamed to `v2/<new dir>`, `config.json.installation` = new installation, `projects/` and
`recent.json` intact.

## Implementation

Core (`core/src/main/kotlin/com/projectjuggler/config/IdeRegistry.kt`):

- Extract `private fun registeredRepositories(): List<IdeConfigRepository>` (the `v2/*` + `loadFromDir` walk) and use
  it in `getRegisteredInstallations()`. Skip repositories whose stored `installation.executablePath` is blank (corrupt
  `config.json`, core-spec gap 10).
- `getRepository(installation)`:
  1. `v2/<installation.directoryName>` has a `config.json` -> `IdeConfigRepository(thatDir, installation)` (no scan;
     the common case).
  2. Else the first of `registeredRepositories()` with `installation.executablePath == installation.executablePath`
     -> return it as loaded (stored installation, so `IdeScopeManager` keys on its real dir name).
  3. Else `IdeConfigRepository.create(installation, v2Dir)` as today.
  KDoc: identity is the executable path; the display name is cosmetic.
- New `fun adoptLegacyRepository(installation: IdeInstallation, legacyExecutablePaths: Collection<String>):
  IdeConfigRepository?`: returns null unless `v2/<installation.directoryName>` does not exist and exactly one
  registered repository has a stored `executablePath` in `legacyExecutablePaths`, and that repository has no
  `projects/*/config/.lock` (reuse `ProjectLockUtils`'s file name constant if one exists, else the literal `.lock`).
  Then `Files.move(oldDir, newDir)` (same parent, a rename), `IdeConfigRepository(newDir, installation).update {
  it.copy(installation = installation) }`, return the new repository. Any `Throwable` during the move -> rethrow as
  `IllegalStateException` with both paths (the caller logs it and falls back). Isolated `idea.vmoptions` files keep the
  old absolute paths until the next launch; launch-time update mode rewrites the marker block (core-spec §6), so no
  rewrite is needed here.

Plugin (`plugin/src/main/kotlin/com/projectjuggler/plugin/services/`):

- `CurrentIdeExecutable.kt` (new): `internal fun currentIdeExecutable(homePath: Path, scriptName: String, platform:
  Platform): Path` -> `MacOS/<script>` (macOS), `bin/<script>.sh` (Linux), `bin/<script>64.exe` (Windows).
  `scriptName` comes from `ApplicationNamesInfo.getInstance().getScriptName()`
  (`com.intellij.openapi.application.ApplicationNamesInfo`, util-8.jar of IntelliJ 2025.1.1; the getter is public, only
  `getAppInfoData`/`initAndGetRawData` are `@ApiStatus.Internal`). `VMOptionsCopy.getFileName` already uses it.
- `IdeInstallationService`:
  - Delete `getIntelliJExecutablePath()`. `detectCurrentInstallation()`: `val exe = currentIdeExecutable(...)`; if it
    does not exist, `LOG.warn` (launches from this IDE will fail); always `IntelliJLocator.findInstallation(exe
    .toString())`. Never key on the home directory.
  - `detectCurrentRepository()`: before `getRepository`, a try/catch (`Throwable`, rethrow `ControlFlowException`)
    around `ideRegistry.adoptLegacyRepository(installation, listOf(PathManager.getHomePath()))`; log an info line
    when it returns non-null, log the error otherwise and continue. Then `getRepository` and
    `autoPopulateIfNeeded` as now.
- No change in `sync-helper/`; `IdeInstallation(idePath, "IDE")` now resolves the plugin's dir through step 2.

## Tests

- Core `config/IdeRegistryTest` (extend; existing `register` helper):
  - "getRepository reuses a registered dir with the same executable under another display name": register
    `IdeInstallation("/ide/first", "First IDE")`, then `getRepository(IdeInstallation("/ide/first", "IDE")).baseDir`
    is the first dir and its `installation.displayName == "First IDE"`.
  - "getRepository ignores registered dirs with a corrupt config": write `v2/x/config.json` = `"{"`; no exception, new
    dir returned.
  - Existing "getRepository creates nothing on disk" still passes.
  - `adoptLegacyRepository`: legacy dir with a project and a recent entry is moved, `config.json.installation` updated,
    `loadAllProjects()` and `RecentProjectsIndex.getRecent` unchanged (needs `KoinTestExtension()`); no candidate ->
    null; target exists -> null and both untouched; `projects/p/config/.lock` present -> null and untouched; two
    candidates -> null.
- Plugin `services/CurrentIdeExecutableTest` (new, pure): the three platforms with `"idea"` and `"pycharm"`.
- Plugin `services/IdeInstallationServiceTest` (edit, test application): flip the gap-10 case to
  `currentInstallation.executablePath == currentIdeExecutable(Path.of(PathManager.getHomePath()),
  ApplicationNamesInfo.getInstance().scriptName, Platform.current()).toString()` and `!= PathManager.getHomePath()`;
  drop "(gap 10)". Add to `beforeSpec`, before the lazy service is first touched: write a legacy
  `IdeInstallation(PathManager.getHomePath(), "legacy")` dir with `mainProjectPath = "/x"`; assert
  `currentRepository.load().mainProjectPath == "/x"` and the legacy dir is gone. Check first what the test
  application's home path and script name are (the plugin-tests session recorded it); if `currentIdeExecutable` is
  absent there, the warning path is what runs, which is fine.

Manual check: `./gradlew :plugin:runIde`, Recent Projects -> sync an isolated project's plugins while inside it
(self-shutdown sync); the helper must find the project and the IDE restarts. Optional, if a non-IDEA JetBrains IDE is
installed: `buildPlugin`, install the zip there, open the popup; `<base>/v2/` gets a `<name>-<hash>` dir whose
`config.json` executable ends in `MacOS/<script>`.

## Docs

- `docs/plugin-spec.md` §2: `currentInstallation` resolves `<home>/{MacOS|bin}/<scriptName>[.sh|64.exe]` from
  `ApplicationNamesInfo.scriptName`, warns when missing, never uses the home dir; `currentRepository` first adopts a
  legacy home-keyed dir. §13: drop gap 10 from the pinned list. Known gaps: delete item 10.
- `docs/core-spec.md` §4: `getRepository` resolution order (exact dir, then same `executablePath`, then new);
  `adoptLegacyRepository`. §2.1: note that `directoryName` is only the name for new dirs; lookup is by executable.
- `docs/sync-helper-spec.md` gap 10: rewrite to "the placeholder display name is persisted only if the helper creates
  a new ideDir (no plugin ran for that executable); resolution goes through `IdeRegistry.getRepository` by executable
  path." Coordinate with any sync-helper gap plan that touches the same item.

## Sessions

Fable 5.1, default effort.

1. **Done** in `resolve ide dirs by executable path and adopt legacy dirs`. **Core registry** (~15%). Reads: this plan, `CLAUDE.md`, `IdeRegistry.kt`, `IdeConfigRepository.kt`,
   `IdeInstallation.kt`, `IdeRegistryTest.kt`, `util/ProjectLockUtils.kt`. Does: core Implementation and core Tests,
   core-spec edits. Commit, e.g. `resolve ide dirs by executable path and adopt legacy dirs`. Marks this entry
   `**Done** in <subject>`.
2. **Plugin** (~15%). Reads: this plan, `IdeInstallationService.kt`, `VMOptionsCopy.kt`,
   `IdeInstallationServiceTest.kt`, plugin-spec §2, sync-helper-spec gap 10. Does: plugin Implementation and Tests,
   remaining Docs, manual check, Finishing.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:test`
3. `./gradlew :sync-helper:build`
4. `./gradlew :plugin:verifyPlugin`
5. Manual check from Tests.

## Finishing

- Apply the Docs section; `CLAUDE.md` "Multi-IDE data model": add that repositories are looked up by executable path.
- Delete `plans/plugin-gap-10-current-ide-identity.md` in the commit of session 2.
- Per session: `git status` / `git diff`; stage only touched files by path. Commit in repo style (lowercase imperative
  subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject for
  session 2: `derive the current ide executable from its script name`.
