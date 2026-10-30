# Keep `sync-in-progress.lock` out of signal cleanup

Core-spec known gap 1.

## Problem

`ShutdownSignalManager` stores two kinds of files in `<projRoot>/signals/`: the JSON `stop-request.json` and the
OS-level lock file `sync-in-progress.lock` used by `acquireSyncLock` (`FileChannel.tryLock()`). Three code paths treat
the lock file as a disposable signal:

- `cleanupStaleSignals` (`core/src/main/kotlin/com/projectjuggler/core/ShutdownSignalManager.kt`) decodes every file as
  `StopRequestSignal` and deletes anything that fails to parse. The lock file is not JSON, so it is always deleted. The
  plugin's `ShutdownSignalService.startMonitoring` calls it on every isolated IDE start, so an IDE that boots while a
  sync-helper holds the lock unlinks the lock file under it.
- `cleanup` deletes every file in `signals/`. `ProjectLauncher.doWithLock` calls it in `finally` before
  `syncLock.close()`, so the file is unlinked while still locked.
- `SyncLock.close()` releases the lock and then `Files.deleteIfExists(lockFile)`.

On Unix an advisory lock belongs to the inode, not the name. Once the file is unlinked, a second process calling
`acquireSyncLock` creates a fresh file with the same name and locks it successfully, while the first still holds a
lock on the unlinked inode. Two syncs now run against the same project. Even the delete-after-release in `close()`
races: process B can open the old inode and lock it after A releases, then A unlinks the name and process C creates and
locks a new file; B and C both believe they hold the lock.

Minimal reproduction: start a stop-and-sync for project P from IDE X; while it waits for P to exit, P's new instance
(or any isolated IDE whose `ShutdownSignalService` resolves P) runs `cleanupStaleSignals(P)`; start a second sync of P
from another IDE: it acquires the lock instead of failing with "Sync already in progress".

Decision (with the user): the lock file is never deleted. It is a zero-byte file under `signals/`, created on first
sync and left in place forever; `cleanup`/`cleanupStaleSignals` skip it by name, and `SyncLock.close()` only releases
and closes the channel. `ProjectCleaner.cleanProject` still removes it with the whole `<projRoot>`.

## Target behavior

| Case | Before | After |
|---|---|---|
| `cleanup(project)` while a sync lock is held | lock file deleted | `stop-request.json` deleted, lock file kept |
| `cleanupStaleSignals(project)` with a lock file present | lock file deleted (not JSON) | lock file kept; other unparseable files still deleted |
| `cleanupStaleSignals` with a stop request older than `maxAgeMinutes` | deleted | deleted (unchanged) |
| `SyncLock.close()` | release, close, delete file | release, close; file stays |
| Second `acquireSyncLock` after another holder ran `cleanup`/`cleanupStaleSignals` | succeeds (two syncs) | returns null |
| `acquireSyncLock` after the previous holder closed | succeeds on a new file | succeeds on the same file |

## Implementation

`core/src/main/kotlin/com/projectjuggler/core/ShutdownSignalManager.kt` (package `com.projectjuggler.core`):

- Add to the `companion object`: `const val SYNC_LOCK_FILE_NAME = "sync-in-progress.lock"` and
  `const val STOP_REQUEST_FILE_NAME = "stop-request.json"`. Use them in `acquireSyncLock` and `getSignalFile`.
- Add a private `fun Path.isSyncLock() = fileName.toString() == SYNC_LOCK_FILE_NAME` (or a `File` variant; both
  methods iterate `File`s today, so prefer switching them to `Files.list(signalsDir).use { ... }` on `Path`).
- `cleanup`: iterate the directory, skip `isSyncLock()`, delete the rest.
- `cleanupStaleSignals`: skip `isSyncLock()` before attempting to decode; keep the existing parse-failure deletion for
  everything else. Replace `catch (e: Exception)` with `catch (e: Throwable)` and log to stderr per the project rule
  (core has no `ControlFlowException`; nothing to rethrow).
- `SyncLock.close()`: remove `Files.deleteIfExists(lockFile)`. The `lockFile` constructor parameter then has no reader;
  drop it from `SyncLock` and from the call site in `acquireSyncLock`.
- Update the KDoc on `cleanup`, `cleanupStaleSignals` and `SyncLock` to say the lock file is permanent.

No change in `ProjectLauncher.doWithLock`: its `finally` order (`cleanup` then `syncLock.close()`) becomes correct once
`cleanup` skips the lock file.

Related plans: `plans/core-gap-02-stop-request-fields.md` also edits `ShutdownSignalManager.createStopRequest` and
`ProjectLauncher`. The two changes touch different methods and are independent.

## Tests

`core/src/test/kotlin/com/projectjuggler/core/ShutdownSignalManagerTest.kt` (Kotest `StringSpec`, already registers
`KoinTestExtension()`). Build the repository with
`IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))` and locate the signals dir via
`DirectoryManager.getInstance(repo).getProjectRoot(project).resolve("signals")`.

New cases:

- "cleanup keeps the sync lock file": acquire the lock, create a stop request, call `cleanup`; assert
  `signals/stop-request.json` is gone, `signals/sync-in-progress.lock` exists, and a second `acquireSyncLock`
  returns null; close the lock.
- "cleanupStaleSignals keeps the sync lock file and deletes unparseable files": acquire the lock, write
  `signals/garbage.txt`, call `cleanupStaleSignals(project, 5)`; assert the lock file exists, `garbage.txt` is gone, a
  second `acquireSyncLock` returns null.
- "cleanupStaleSignals deletes stop requests older than maxAgeMinutes": write a `stop-request.json` by hand with
  `timestamp = Instant.now().minus(10, ChronoUnit.MINUTES)` (serialize a `StopRequestSignal`), call
  `cleanupStaleSignals(project, 5)`; assert `readStopRequest` is null. Replace the existing "should cleanup stale
  signals older than specified age" test, whose second half only calls `cleanup`.
- "closing the sync lock keeps the file": acquire, close, assert the file exists; acquire again, assert non-null.

The existing "should acquire and release sync lock" case still passes.

## Docs

`docs/core-spec.md`:

- §3 on-disk layout: comment on `sync-in-progress.lock` becomes "permanent, §8".
- §8 step 6: "Always: delete every file in `signals/` except `sync-in-progress.lock`, then release the sync lock. The
  lock file is never deleted."
- §8.1: `cleanupStaleSignals` "deletes files in `signals/` other than `sync-in-progress.lock` whose embedded timestamp
  is older than the limit, and any such file that does not parse as a stop request."
- Known gaps: delete item 1.

## Sessions

1. Single session (Fable 5.1, default effort), ~10% context: read this plan, `ShutdownSignalManager.kt`,
   `ProjectLauncher.doWithLock`, `ShutdownSignalManagerTest.kt`; implement, test, update the spec, finish.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.core.ShutdownSignalManagerTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual: run `:plugin:runIde`, open an isolated project, trigger "stop and sync" of it from a second isolated IDE;
   after it finishes, `~/.project-juggler-test/v2/<ide>/projects/<id>/signals/` contains only
   `sync-in-progress.lock`.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-01-sync-lock-cleanup.md` in the same commit.
- Check `git status`/`git diff`; stage only the changed files by path (never `git add -A`). Commit in the style of
  recent commits: lowercase imperative subject (e.g. `keep the sync lock file out of signal cleanup`), body with what
  changed and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List anything left
  uncommitted in the reply.
