# Persistence robustness: corrupt files, atomic update, locked recents

Fixes known gap 10 of `docs/core-spec.md`.

## Problem

Files: `core/src/main/kotlin/com/projectjuggler/config/IdeConfigRepository.kt`, `config/RecentProjectsIndex.kt`,
`config/IdeRegistry.kt`, `util/FileLockUtils.kt`.

1. `IdeConfigRepository.loadFromDir(ideDir)` builds a repository with `IdeInstallation("", "")`, calls `load()`, and
   because `load()` swallows parse errors into `IdeConfig.default(installation)`, a corrupt or empty `config.json`
   yields
   a repository whose installation has a blank executable path. `IdeRegistry.getRegisteredInstallations()` then lists a
   phantom IDE.
2. `loadMetadata` has no error handling. One corrupt `projects/<id>/metadata.json` throws out of `loadAllProjects()`
   (used by `ProjectManager.listAll/listSyncable`, "sync all") and out of `RecentProjectsIndex.getRecent()` (the Recent
   Projects popup), hiding every other project.
3. `update(transform)` is `load()` then `save()`: two separate `withFileLock` acquisitions, so two processes (plugin and
   sync-helper) can interleave and lose a write.
4. `recent.json` is read and written without any lock; `recordOpen` is a read-modify-write.
5. `FileLockUtils.withFileLock` does `if (!exists) createFile`, which races between two processes
   (`FileAlreadyExistsException`).

Decision made with the user: fix all in core, keep the "degrade to default" behavior for `config.json` reads in `load()`
and make corrupt metadata a skipped entry (logged) instead of an error.

## Target behavior

| Case | Before | After |
|---|---|---|
| `loadFromDir`, corrupt or empty `config.json` | repository with blank installation | `null`; `IdeRegistry` omits it |
| `loadFromDir` on a parseable config with blank `executablePath` | repository | `null` |
| `load()` on corrupt `config.json` | default | default (unchanged) |
| `loadAllProjects()` with one corrupt `metadata.json` | throws | returns the others; stderr warning |
| `getRecent()` with a recent entry whose metadata is corrupt | throws | skips that entry |
| `loadProjectMetadata(path)` on corrupt file | throws | `null` |
| two processes call `update` concurrently | one write may be lost | both applied (one lock around the whole update) |
| two processes `recordOpen` concurrently | file may be clobbered | serialized under the `recent.json` lock |
| two processes create the lock file at once | `FileAlreadyExistsException` | tolerated |

## Implementation

`config/IdeConfigRepository.kt`:
- `private fun readConfigOrNull(): IdeConfig?`: null if the file is missing; otherwise `withFileLock(configFile)` and
  decode; `catch (e: Throwable)` logs `[IdeConfigRepository] Ignoring corrupt <file>: <message>` to stderr and returns
  null. `load()` becomes `readConfigOrNull() ?: IdeConfig.default(installation)`.
- `update(transform)`: single `FileLockUtils.withFileLock(configFile) { val current = decodeOrDefault();
  write(transform(current)) }`.
  Extract `private fun decodeConfigText(text: String): IdeConfig?` and `private fun writeConfig(config)` so `load`,
  `save` and `update` share them; `save` keeps `Files.createDirectories(baseDir)` + lock + write.
- `private fun loadMetadata(projectDir)`: wrap the decode in `try/catch (e: Throwable)`, log the same way, return null.
  `loadAllProjects` and `loadProjectMetadata` need no change beyond that.
- `companion loadFromDir(ideDir)`: use a private constructor path that avoids the fake-installation dance:
  `val config = IdeConfigRepository(ideDir, IdeInstallation("", "")).readConfigOrNull() ?: return null` (keep
  `readConfigOrNull` `internal` or make `loadFromDir` call a private static reader on the file). Return null when
  `config.installation.executablePath.isBlank()`; also log.

`config/RecentProjectsIndex.kt`:
- `recordOpen` and `remove`: wrap body in `FileLockUtils.withFileLock(recentFile) { ... }` (the lock helper creates
  parent dirs and the file, so `Files.createDirectories` in `saveEntries` can go). `loadEntries` reads inside the same
  lock when called from those two; `getRecent` takes the lock just for the read.
- `loadEntries`: treat a blank file as empty (the lock helper may have created it); `catch (e: Throwable)` instead of
  `SerializationException`, log to stderr.
- `maxRecentProjects` is read via `ideConfigRepository.load()` before taking the recents lock (different file, avoids
  nested locks on `config.json` inside the recents lock; order is always config then recents).

`util/FileLockUtils.kt`: replace `if (!file.exists()) Files.createFile(file)` with
`try { Files.createFile(file) } catch (e: FileAlreadyExistsException) { }`.

`config/IdeRegistry.kt`: no change (already `mapNotNull`).

Logging: core has no logger; follow `IntelliJLauncher`/`ProcessLauncherImpl` and use `System.err.println` with a
`[ClassName]` prefix.

Related plans (independent): `plans/core-gap-01-sync-lock-cleanup.md` and others touch `ShutdownSignalManager`, not
these files. None of them depend on this plan.

## Tests

New `core/src/test/kotlin/com/projectjuggler/config/IdeConfigRepositoryTest.kt` (`StringSpec`, `createTempDir`; no Koin
needed):
- "loadFromDir returns null for corrupt config": write `{not json` to `<dir>/config.json`;
  `IdeConfigRepository.loadFromDir(dir) shouldBe null`.
- "loadFromDir returns null for empty config file" (0 bytes).
- "loadFromDir returns null for blank executable path": valid JSON with `"installation": {"executablePath": "",
  "displayName": "x"}`.
- "loadFromDir returns repository for valid config": `save(IdeConfig.default(installation))` then load; installation
  equal.
- "IdeRegistry omits a corrupt ide dir": `IdeRegistry(tempBase)` (constructor is `internal`, same module), one valid dir
  via `getRepository(inst).save(...)`, one dir with corrupt `config.json`; `getRegisteredInstallations()` has size 1
  (mock `IntelliJLocator` not needed, the method does not call it).
- "load returns default for corrupt config" (existing behavior, pinned).
- "loadAllProjects skips a corrupt metadata file": two project dirs, one via `saveProjectMetadata`, one with
  `metadata.json` = `garbage`; result has size 1; `loadProjectMetadata(corruptId) shouldBe null`.
- "update holds one lock for read-transform-write": `mockkObject(FileLockUtils)` with `callOriginal`, call `update { ...
  }`
  once, `verify(exactly = 1) { FileLockUtils.withFileLock(any(), any()) }`; `unmockkObject` afterwards. (Two writers in
  one JVM cannot be tested: a second `FileChannel.lock()` on the same file throws `OverlappingFileLockException`; the
  cross-process case is the manual check below.)

New `core/src/test/kotlin/com/projectjuggler/config/RecentProjectsIndexTest.kt` (`StringSpec`, `KoinTestExtension()`,
`RecentProjectsIndex.getInstance(repo)`):
- "recordOpen trims to maxRecentProjects": set `maxRecentProjects = 3` via `repo.update`, record 5 paths (temp dirs
  with metadata saved via `ProjectManager.getInstance(repo).registerOrUpdate`), `getRecent(10)` has size 3 and the
  newest first.
- "recordOpen moves an existing entry to the head".
- "getRecent skips an entry whose metadata is corrupt": record two, overwrite one `metadata.json` with garbage; size 1.
- "corrupt recent.json reads as empty": write `garbage` to `recent.json`; `getRecent(10)` empty; `recordOpen` then
  works.
- "remove drops the entry".

`util/FileLockUtilsTest.kt` (new, small): "withFileLock creates missing parent and file", "withFileLock returns the
block
result", "withFileLock tolerates a pre-created file".

Not testable here: true cross-process interleaving. Manual check: run `./gradlew :plugin:runIde`, open an isolated
project, and while it starts run a sync-helper `--all-projects` sync from a terminal; `config.json` must stay valid
JSON.

## Docs

`docs/core-spec.md`:
- §3: `recent.json` is now locked like the others (remove "`recent.json` is not locked").
- §3.2: "A corrupt file is skipped (logged to stderr) and treated as missing."
- §4: `update(transform)` holds one lock across read, transform and write; `loadFromDir` returns null for corrupt
  config or blank executable path; `loadAllProjects` skips corrupt entries.
- §12: "Readers of config, recents and project metadata degrade (default / empty / skipped) on corruption."
- Delete item 10 from "Known gaps"; in item 15 remove "`recent.json` trimming".

## Sessions

1. Single session (Fable 5.1, default effort, ~20% context): read this plan, `CLAUDE.md`, the four source files, the
   existing `MainProjectServiceTest.kt` (test style) and `KoinTestExtension.kt`. Implement, write the three test specs,
   update the spec, verify, finish. Done when `:core:test` is green and plugin and sync-helper compile.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.config.*"`
2. `./gradlew :core:test --tests "com.projectjuggler.util.FileLockUtilsTest"`
3. `./gradlew :core:test`
4. `./gradlew :plugin:compileKotlin`
5. `./gradlew :sync-helper:build`
6. Manual: corrupt one `metadata.json` under `~/.project-juggler-test/v2/<ide>/projects/` and start `runIde`; the Recent
   Projects popup must still list the other projects.

## Finishing

- Spec sections above carry the decisions; CLAUDE.md unchanged.
- Delete `plans/core-gap-10-persistence-robustness.md` in the same commit.
- `git status` / `git diff`; stage by path only (the four sources, three tests, spec, this plan). Commit in the repo
  style: lowercase imperative subject such as `skip corrupt metadata and lock recent.json`, a body explaining the lost
  update and phantom installation, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Report any
  file left out of the commit.
