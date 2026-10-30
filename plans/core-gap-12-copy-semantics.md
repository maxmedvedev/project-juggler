# Core gap 12: retry failed first-open copies, keep the project's own files on a forced config sync

## Context

`docs/core-spec.md` "Known gaps" item 12: `copyIfFirstOpen` can leave a partial copy that is never retried. A forced
config sync wipes the project's own `.lock` and `recentProjects.xml`.

Both live in `core/src/main/kotlin/com/projectjuggler/util/DirectoryCopier.kt`, called from
`core/src/main/kotlin/com/projectjuggler/core/DirectoryManager.kt`:

- `copyIfFirstOpen(source, destination, excludedDirectories, excludedFiles, excludedPatterns)` copies only when the
  destination is empty, and on any exception returns `false` and leaves whatever was copied in place. Example: the
  base config is 300 MB, the disk fills halfway, the IDE launches with half a config; every later launch sees a
  non-empty `config/` and never retries.
- `copy(source, destination, ...)` calls the private `clearDirectory(destination)`, which deletes everything, and
  then copies with the exclusions. The exclusions (`.lock`, `recentProjects.xml`, `recentProjectDirectories.xml`,
  `options/recentProjects.xml`, directory `plugins`) are meant to keep the project's own state out of the sync, but
  `clearDirectory` deletes those very files from the destination first. With `stopIfRunning = false` the running
  isolated IDE loses its `config/.lock` (so `isProjectOpen` turns false while it still runs) and its own recent
  projects list.

Decisions (planning session with the user): clear the destination on a failed first-open copy so the next launch
retries; make `clearDirectory` honor the same exclusions as the copy so excluded destination entries survive a
forced sync; share one exclusion predicate between copy and clear. The exclusion sets stay in `DirectoryManager`
but become `internal` so tests can use them.

Related plans: `plans/core-gap-13-process-launcher.md` and `plans/core-gap-11-git-helpers.md` do not touch these
files. If `plans/core-gap-09-running-detection.md` or `plans/core-gap-14-main-instance-detection.md` is implemented
first, `DirectoryManager.kt` has extra members; the changes here are independent of them.

## Target behavior

| Case                                                      | Result                                                 |
|-----------------------------------------------------------|--------------------------------------------------------|
| `copyIfFirstOpen`, destination empty, copy succeeds       | `true`, full copy (unchanged)                          |
| `copyIfFirstOpen`, destination empty, copy throws midway  | `false`; destination emptied again, next call retries  |
| `copyIfFirstOpen`, destination non-empty                  | `false`, nothing touched (unchanged)                   |
| `copy` with exclusions; destination holds `.lock`, `options/recentProjects.xml`, `plugins/x.jar` | all survive |
| `copy` with exclusions; destination holds `old.xml` not in source | `old.xml` deleted (unchanged)                  |
| `copy` with no exclusions (plugins sync)                  | destination fully cleared then copied (unchanged)      |
| `copy`, source invalid                                    | `IllegalArgumentException`, nothing deleted (unchanged)|

## Implementation

### `util/DirectoryCopier.kt` (object, package `com.projectjuggler.util`)

- Add a private value class or data class `Exclusions(directories: Set<String>, files: Set<String>,
  patterns: Set<String>)` with two members used by both walkers:
  - `fun excludesDirectory(dir: Path): Boolean` = `dir.fileName?.toString() in directories`.
  - `fun excludesFile(root: Path, file: Path): Boolean` = `file.fileName.toString() in files ||
    root.relativize(file).toString().replace(File.separator, "/") in patterns`.
  The public signatures keep their three `Set<String>` parameters (callers and tests are unchanged); each public
  method builds the `Exclusions` once.
- `copyDirectoryRecursively(source, destination, exclusions)`: same logic as today, routed through the predicate.
- `clearDirectory(root, exclusions)`:
  - `preVisitDirectory`: `SKIP_SUBTREE` when `dir != root && exclusions.excludesDirectory(dir)`.
  - `visitFile`: skip when `exclusions.excludesFile(root, file)`; otherwise delete.
  - `postVisitDirectory`: delete `dir` only when `dir != root` and the directory is now empty (an excluded file kept
    inside it means the directory stays). Use `Files.list(dir).use { it.findAny().isEmpty }` (reuse
    `isEmptyDirectory`).
  - `copy` passes its exclusions to both `clearDirectory` and `copyDirectoryRecursively`.
- `copyIfFirstOpen`: in the `catch`, call `runCatching { clearDirectory(destination, Exclusions.NONE) }` before
  returning `false`, then log the original failure to stderr (`System.err.println("[DirectoryCopier] copy from
  $source to $destination failed: $e")`). Catch `Throwable` per `CLAUDE.md`. Clearing with no exclusions is right
  here: the destination was empty before, so everything in it is our partial copy.
- Update the KDoc of `copy` ("excluded entries already present in the destination are preserved") and
  `copyIfFirstOpen` ("a failed copy is rolled back so the next call retries").

### `core/DirectoryManager.kt`

- Change the three top-level `private val EXCLUDED_FILES / EXCLUDED_PATTERNS / EXCLUDED_DIRECTORIES` to `internal`
  and group them in an `internal object ConfigCopyExclusions { val directories; val files; val patterns }` (one
  named holder instead of three loose vals). Update the two call sites (`copyBaseConfigIfNeeded`,
  `syncConfigFromBase`). No behavior change in this file.

## Tests

`core/src/test/kotlin/com/projectjuggler/util/DirectoryCopierTest.kt` (Kotest `StringSpec`, `createTempDir`).
Add:

- `"copy should preserve excluded files and directories in the destination"`: destination has `.lock` (content
  `"1234"`), `options/recentProjects.xml`, `plugins/a.jar`, `stale.xml`; source has `options/other.xml`,
  `new.txt`. Call `copy(source, destination, ConfigCopyExclusions.directories, ConfigCopyExclusions.files,
  ConfigCopyExclusions.patterns)`. Assert `.lock` still reads `"1234"`, `options/recentProjects.xml` and
  `plugins/a.jar` exist, `stale.xml` is gone, `options/other.xml` and `new.txt` exist.
- `"copy should not copy excluded source entries"`: source has `.lock` and `plugins/b.jar`; after `copy` with the
  config exclusions the destination has neither.
- `"copy without exclusions should clear everything"`: destination has `.lock`; `copy(source, destination)` removes
  it (guards the plugins-sync path).
- `"copyIfFirstOpen should roll back a partial copy so a retry copies again"`: source has `a.txt` and
  `sub/b.txt`; destination exists and is empty. Make the copy fail by removing all permissions from `source/sub`
  (`Files.setPosixFilePermissions(sub, emptySet())`); guard with
  `enabledIf = { !Platform.isWindows() && System.getProperty("user.name") != "root" }`. Expect `false` and an empty
  destination. Restore the permissions, call again, expect `true` and both files present. (If permissions prove
  flaky on CI, fail the copy another deterministic way, e.g. a destination path that is a regular file where the
  source has a directory, and record the choice in the commit body.)

`DirectoryManagerTest.kt` needs no change; `ConfigCopyExclusions` is referenced from the copier test only.

## Docs

`docs/core-spec.md`:

- §7.1 "Base config copy exclusions" paragraph: add "A failed first-open copy is rolled back (destination emptied)
  so the next launch retries."
- §8 step 4 "config:" bullet: "wipe `<projRoot>/config/` contents **except the excluded entries** and recopy base
  config with the §7.1 exclusions."
- §11 `DirectoryCopier` bullet: `copyIfFirstOpen` "rolls back and returns false on error"; `copy` "clears `dst`
  except entries matching the exclusions, then copies".
- "Known gaps": delete item 12.

## Sessions

1. Single session (Fable 5.1, current effort), ~12% of context. Reads: this plan, `CLAUDE.md`,
   `util/DirectoryCopier.kt`, `core/DirectoryManager.kt`, `util/DirectoryCopierTest.kt`, spec §7.1, §8, §11 and
   "Known gaps". Implements Implementation, Tests, Docs; runs Verification; does Finishing. Done when
   `./gradlew :core:test` is green and `:plugin:compileKotlin` passes.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.util.DirectoryCopierTest"`
   `./gradlew :core:test --tests "com.projectjuggler.core.DirectoryManagerTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual: `./gradlew :plugin:runIde`, open an isolated project, keep it running, and from the sandbox IDE run
   "Sync config" for that project **without** stop/restart. Confirm that
   `~/.project-juggler-test/v2/<ide>/projects/<id>/config/.lock` still exists afterwards and the isolated IDE's
   Recent Projects list is unchanged.

## Finishing

- Apply the Docs changes; `CLAUDE.md` is unchanged.
- Delete `plans/core-gap-12-copy-semantics.md` in the same commit.
- `git status` / `git diff` first; stage by path only:
  `core/src/main/kotlin/com/projectjuggler/util/DirectoryCopier.kt`,
  `core/src/main/kotlin/com/projectjuggler/core/DirectoryManager.kt`,
  `core/src/test/kotlin/com/projectjuggler/util/DirectoryCopierTest.kt`, `docs/core-spec.md`, the deleted plan.
  Commit like the recent commits: lowercase imperative subject, body with what and why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List anything left out of the commit in the reply.
