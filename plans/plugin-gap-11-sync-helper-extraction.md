# Extract sync-helper once per content, off EDT, with output in a log file (plugin-spec gap 11)

## Context

`docs/plugin-spec.md`, Known gaps 11: "`BundledSyncHelper` extracts a full distribution into a new temp directory once
per IDE session and never deletes it. Extraction happens on the calling thread. The helper process inherits the IDE's
stdio." The same facts are `docs/sync-helper-spec.md` gaps 4 (stdio disappears, errors invisible) and 9 (temp dirs).

**Depends on `plans/plugin-tests.md` landing first.** After it, `performSelfShutdownSync(project, notificationMessage,
helperArgs)` in `plugin/src/main/kotlin/com/projectjuggler/plugin/actions/Sync.kt` takes the complete args (the
`syncType` parameter is gone, `SyncType.cliFlag` is already in `helperArgs`), and
`plugin/src/test/kotlin/com/projectjuggler/plugin/util/BundledSyncHelperTest.kt` pins the current extraction (a
`sync-helper-*` temp dir, re-extraction after deletion, `afterSpec` cleanup "(gap 11)"). This plan rewrites that test.

Today (`plugin/src/main/kotlin/com/projectjuggler/plugin/util/BundledSyncHelper.kt`): `getExecutable()` caches the path
in a static field; on a miss it calls `Files.createTempDirectory("sync-helper-")` and copies `sync-helper/bin` and
`sync-helper/lib` (~3 MB, 10 jars) out of the plugin classloader (jar entries or `Files.walk` for file URLs).
`performSelfShutdownSync` runs on EDT (popup `onChosen` in `SyncSettingsSubAction` / `SyncAllProjectsAction`): it
extracts, `ProcessBuilder(...).inheritIO().start()`, `Thread.sleep(100)`, then `invokeLater { exit() }`. When the
IDE was started from the Dock/launcher its stdout goes nowhere, so a helper failure (lock held, shutdown timeout,
missing base path, no `java`) leaves no trace.

Decisions (project conventions decide these, no question):

- Extract into a directory named by a **content fingerprint** of the bundled `sync-helper/` resources, not the plugin
  version: under `runIde` the version stays `0.0.6` while the helper changes. Reuse it across IDE sessions; remove
  other fingerprints after a successful extraction.
- Extract into `<fp>.tmp-*` and `Files.move(..., ATOMIC_MOVE)` to `<fp>`, so a crash mid-extraction never leaves a
  directory that looks complete.
- Helper stdout+stderr go to `PathManager.getLogDir()/sync-helper.log`, overwritten per run (in an isolated instance
  that is `<projRoot>/logs`, next to `idea.log`). Stdin is closed right after start.
- The spawn runs in a non-cancellable `Task.Backgroundable`; `exit()` happens in `onSuccess` on EDT.
- `Thread.sleep(100)` stays (now off EDT): the restart race it half-addresses is `docs/sync-helper-spec.md` gap 1 and
  is out of scope.
- The rewritten `performSelfShutdownSync` follows the try-catch rule, which removes the last part of plugin-spec gap 4
  ("`performSelfShutdownSync` catches `Exception`") left after plugin-tests. The hardcoded notification texts stay
  (gap 3); the one new string goes into the bundle.

Overlap: `plans/plugin-gap-03-bundle-strings-and-catches.md` also edits `performSelfShutdownSync` (its catch and its two
hardcoded strings) and the two catches in `BundledSyncHelper`. Whichever lands second merges: keep this plan's
`Task.Backgroundable` shape (no try/catch in `performSelfShutdownSync`) and gap-03's bundle keys for the notification
texts; drop the gap-4 deletion below if gap-03 already did it. Rewritten `BundledSyncHelper` catches follow the rule
(`Throwable`, `ControlFlowException` rethrown), which subsumes gap-03's change to that file.
`plans/plugin-gap-14-edt-io-and-koin-init.md` moves the callers' `onChosen` bodies to a pooled thread and annotates
`performSelfShutdownSync` with `@RequiresBackgroundThread`. Compatible: the task can be queued from any thread; keep
the annotation if present, and keep the `Task.Backgroundable` (progress UI, `onSuccess` on EDT) either way.

Decision (made with the user, 2026-10-30): the extraction root is `PathManager.getSystemDir()/project-juggler/
sync-helper/` of the running IDE (`<projRoot>/system/...` in an isolated instance), because only this IDE process
touches it, so deleting stale fingerprints cannot hit another IDE's helper, and `ProjectCleaner` removes it with the
project (~3 MB each). Rejected: a shared root under the base dir (IDEs on different plugin versions delete each
other's in-use dirs); a per-session temp dir deleted at next start (no reuse, strays after crashes).

Also removed once per JVM, best effort: legacy `java.io.tmpdir/sync-helper-*` directories created by earlier versions,
recognized by containing `bin/` and `lib/sync-helper.jar`, last modified more than 1 hour ago (an older plugin in
another IDE may be mid-sync from one).

## Target behavior

| Situation | Result |
|---|---|
| first self-sync with this plugin content | extracted to `<root>/<fp>/{bin,lib}`, helper started from there |
| later self-sync, same session or later sessions | no extraction, same path |
| `<root>/<fp>` deleted while running | re-extracted on next call |
| plugin updated (new fingerprint) | new `<root>/<fp2>`; `<root>/<fp>` and leftover `*.tmp-*` removed, best effort |
| two calls in parallel | `@Synchronized`; one extraction |
| extraction or spawn fails | error notification, IDE keeps running, error logged |
| helper prints anything (incl. stack trace on exit 1) | in `<IDE log dir>/sync-helper.log` |
| EDT | no extraction, no process start, no sleep |
| legacy `sync-helper-*` in tmp, >1 h old, has `lib/sync-helper.jar` | deleted on first extraction in this JVM |

## Implementation

`plugin/src/main/kotlin/com/projectjuggler/plugin/util/BundledSyncHelper.kt` (object stays, `internal` members for
tests):

```kotlin
object BundledSyncHelper {
    const val LOG_FILE_NAME = "sync-helper.log"

    /** Must not be called on EDT. */
    @RequiresBackgroundThread
    fun getExecutable(): Path = getExecutable(defaultRoot(), Path(System.getProperty("java.io.tmpdir")))

    @Synchronized
    internal fun getExecutable(root: Path, legacyTempDir: Path?): Path

    /** Starts the helper with stdout+stderr in [logFile] and stdin closed. */
    @RequiresBackgroundThread
    fun start(args: List<String>, logFile: Path = PathManager.getLogDir().resolve(LOG_FILE_NAME)): Process

    internal fun defaultRoot(): Path = PathManager.getSystemDir().resolve("project-juggler").resolve("sync-helper")
    internal fun fingerprint(): String
}
```

- `fingerprint()`: SHA-256 over the sorted list of `"<relative name>:<size>:<crc>"` for every non-directory entry under
  `sync-helper/` (jar mode: `JarEntry.size`/`crc` from the central directory, no decompression); file mode: relative
  path and SHA-256 of the file bytes. First 16 hex chars. Reuse the jar-path parsing and the file/jar split already in
  `extractDirectory`; enumerate entries through one shared helper so fingerprint and extraction walk the same set.
- `getExecutable(root, legacyTempDir)`:
  1. `target = root/<fp>`; `exe = target/bin/sync-helper(.bat)` via the existing `Platform.current()` switch. If
     `exe` exists, return it.
  2. `Files.createDirectories(root)`; `tmp = Files.createTempDirectory(root, "$fp.tmp-")`; extract `bin` and `lib` into
     `tmp` (existing `extractDirectory`); `setExecutable(true)` on Unix; check the executable exists (else
     `IllegalStateException` as today); `Files.move(tmp, target, ATOMIC_MOVE)`. On `FileAlreadyExistsException` or
     `DirectoryNotEmptyException` delete `tmp` and use `target`. On any other `Throwable` delete `tmp` (best effort)
     and rethrow wrapped in `IllegalStateException` as today.
  3. Delete every other entry of `root` (old fingerprints, `*.tmp-*`) recursively; per entry catch `Throwable`, log at
     info and continue (Windows may hold files).
  4. If `legacyTempDir != null` and not yet done in this JVM (`AtomicBoolean`), delete matching legacy dirs as in
     Context, same error handling.
  - Remove `cachedExecutablePath`; the existence check in step 1 replaces it.
- `start(args, logFile)`: `Files.createDirectories(logFile.parent)`;
  `ProcessBuilder(getExecutable().toString(), *args.toTypedArray()).redirectErrorStream(true)
  .redirectOutput(logFile.toFile())`, `start()`, then `process.outputStream.close()`; log the command line and log
  file at info. Use `com.intellij.util.concurrency.annotations.RequiresBackgroundThread`
  (`util` module, public; `ideaIU-2025.1.1-sources.jar!/com/intellij/util/concurrency/annotations/
  RequiresBackgroundThread.java`).

`plugin/src/main/kotlin/com/projectjuggler/plugin/actions/Sync.kt`, `performSelfShutdownSync(project,
notificationMessage, helperArgs)`:

```kotlin
showInfoNotification(notificationMessage, project)
ProgressManager.getInstance().run(object : Task.Backgroundable(
    project, ProjectJugglerBundle.message("progress.sync.helper.starting"), /* canBeCancelled = */ false) {
    override fun run(indicator: ProgressIndicator) {
        BundledSyncHelper.start(helperArgs)
        Thread.sleep(100) // see sync-helper-spec gap 1
    }
    override fun onSuccess() = ApplicationManager.getApplication().exit()
    override fun onThrowable(error: Throwable) {
        thisLogger().warn("Failed to initiate self-shutdown sync", error)
        showErrorNotification("Failed to initiate self-shutdown sync: ${error.message}", project) // gap 3
    }
})
```

No try/catch remains (the task framework routes `ProcessCanceledException` to `onCancel`, which keeps the IDE
running). `onSuccess` runs on EDT, so `invokeLater` is no longer needed. Bundle:
`progress.sync.helper.starting=Starting sync helper...` in `messages/ProjectJugglerBundle.properties` next to the
other `progress.sync.*` keys. Callers (`SyncSettingsSubAction`, `SyncAllProjectsAction`) do not change.

## Tests

Rewrite `plugin/src/test/kotlin/com/projectjuggler/plugin/util/BundledSyncHelperTest.kt` (no test application needed;
`createTempDir` for `root` and `legacyTempDir`; drop the `afterSpec` cleanup and the "(gap 11)" note):

- `getExecutable(root, null)` returns `<root>/<fingerprint()>/bin/sync-helper` (`.bat` on Windows), executable on
  Unix, `lib/sync-helper.jar` and `lib/core.jar` next to `bin/`; `root` has exactly one entry.
- Second call returns the same path and does not re-extract (`lib/core.jar` last-modified time unchanged).
- Delete `<root>/<fp>` -> next call re-extracts to the same path.
- Pre-create `<root>/0000000000000000/bin/x` and `<root>/<fp>.tmp-123/` -> after extraction only `<fp>` is left.
- Pre-create `<root>/<fp>/bin/sync-helper` (valid tree) -> returned as is, siblings untouched (cleanup only after an
  extraction).
- Legacy: in `legacyTempDir` create `sync-helper-old/{bin/,lib/sync-helper.jar}` with mtime 2 h ago,
  `sync-helper-fresh/` same layout with current mtime, `sync-helper-foreign/lib/other.jar` 2 h old -> only
  `sync-helper-old` is deleted. Note: this case must run first in the spec or use a fresh `BundledSyncHelper` state;
  expose the `AtomicBoolean` reset as `internal fun resetLegacyCleanupForTests()` if needed.
- Nothing named `sync-helper-*` appears in `System.getProperty("java.io.tmpdir")` (listing before vs after, filtered
  by name).
- Two threads calling `getExecutable(root, null)` concurrently return the same existing path.
- `fingerprint()` is stable across calls and has 16 hex chars.
- `start(listOf("--bogus"), logFile = <temp>/logs/sync-helper.log)`: `waitFor(60, SECONDS)` is true and the log file
  exists and is non-empty (the helper fails on the missing `--ide` with a stack trace, or the start script reports a
  missing `java`; either is output). Do not assert the exit code.

Out of scope for automated tests, as in plugin-tests: `performSelfShutdownSync` (spawn + exit).

Manual check: `./gradlew :plugin:runIde`, open an isolated project, in it run Recent Projects -> its sub-menu -> Sync
Plugins. A "Starting sync helper..." progress shows, the IDE exits,
`<projRoot>/system/project-juggler/sync-helper/<fp>/` exists, no new `sync-helper-*` in `$TMPDIR`, and
`<projRoot>/logs/sync-helper.log` has the helper output (under
`runIde` it fails with `Project not found`, see sync-helper-spec gap 2, which is fine for this check). Repeat: no
second fingerprint dir.

## Docs

`docs/plugin-spec.md`:
- §6.5: steps become: info notification; non-cancellable background task `progress.sync.helper.starting` that calls
  `BundledSyncHelper.start(helperArgs)` (output to `<log dir>/sync-helper.log`, stdin closed) and sleeps 100 ms; on
  success `Application.exit()` on EDT; any failure -> logged warning and the hardcoded error notification, IDE keeps
  running.
- §9: rewrite the extraction paragraph per Target behavior (root, fingerprint, atomic move, stale and legacy cleanup,
  `@Synchronized`). Remove "Extracted directories are never removed."
- §12 table: "Self-shutdown helper extraction and spawn | `Task.Backgroundable`".
- §13 (added by plugin-tests): remove 11 from the gaps pinned by tests; update the `BundledSyncHelperTest` line.
- Known gaps: delete item 11; in item 4 delete the remaining `performSelfShutdownSync` clause (delete the item if
  nothing is left).

`docs/sync-helper-spec.md`:
- §5.2 and §5.4 rewritten to match; §6: "nobody reads the exit code" stays, add "output is in the IDE's
  `sync-helper.log`".
- Known gaps: delete 9; reword 4 to "Output goes to `<IDE log dir>/sync-helper.log`, but nothing surfaces it; errors
  are still invisible unless the user opens the file." (the visibility part is not fixed here).

## Sessions

1. Single session, Fable 5.1 at default effort, ~20% context. Reads: this plan, `CLAUDE.md`,
   `util/BundledSyncHelper.kt`, `actions/Sync.kt`, `BundledSyncHelperTest.kt`, plugin-spec §6.5/§9/§12/§13,
   sync-helper-spec §5-6. Implements, tests, manual check, docs, Finishing. Done when `:plugin:test` passes and gap 11
   is gone from the plugin spec.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.util.BundledSyncHelperTest"`
2. `./gradlew :plugin:test`
3. `./gradlew :plugin:buildPlugin` and `./gradlew :plugin:verifyPlugin` (no new internal-API usages reported)
4. Manual check from Tests.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-11-sync-helper-extraction.md` in the same commit.
- `git status` / `git diff`; stage by path only (`BundledSyncHelper.kt`, `Sync.kt`, the bundle, the test, both specs,
  this plan). Commit in the style of recent commits (lowercase imperative subject, body with the why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `reuse one sync-helper extraction per content and log its output`. Report anything left uncommitted.
