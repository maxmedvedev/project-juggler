# sync-helper behavioral specification

This document describes what the `sync-helper` module does today, as observed from the code, and the contract between it and the `plugin` that spawns it. It builds on [core-spec.md](core-spec.md); anything about the sync protocol itself (locks, stop requests, copying) is specified there in §8 and only referenced here. Questionable behavior is described as-is and listed under [Known gaps](#known-gaps).

Conventions follow the core spec: `<base>`, `<ideDir>`, `<projRoot>`.

## 1. Purpose

A running IDE cannot sync its own isolated state: it would have to stop itself, copy files into the directories it is using, and start again. `sync-helper` is a minimal standalone JVM program that performs `ProjectLauncher.syncProject` from outside the IDE process with `stopIfRunning = true, autoRestart = true`. It exists only for this self-sync case; syncing a *different* project is done in-process by the plugin (`performSyncWithProgress`).

## 2. Module shape

- Single source file: `SyncHelperMain.kt`, top-level `main`, class `com.projectjuggler.synchelper.SyncHelperMainKt`.
- Dependencies: `:core` and `koin-core`. No IntelliJ Platform dependency. JDK 17 toolchain.
- Built with the Gradle `application` plugin, `applicationName = "sync-helper"`. `installDist` produces `build/install/sync-helper/{bin,lib}` with the standard start scripts `bin/sync-helper` (Unix) and `bin/sync-helper.bat` (Windows).
- No tests.

## 3. Command line

```
sync-helper --ide <ide-path> --path <project-path>  [--all|--vmoptions|--config|--plugins]
sync-helper --ide <ide-path> --all-projects         [--all|--vmoptions|--config|--plugins]
```

| Argument         | Takes value | Meaning |
|------------------|-------------|---------|
| `--ide`          | yes         | `IdeInstallation.executablePath` of the IDE whose repository to use. Required. |
| `--path`         | yes         | A tracked project path. `~` is expanded, the path is normalized and must exist. |
| `--all-projects` | no          | Sync every project under `<ideDir>/projects/` except the main project. |
| `--all`          | no          | vmoptions + config + plugins. |
| `--vmoptions`    | no          | Regenerate `idea.vmoptions`. |
| `--config`       | no          | Wipe and recopy `config/`. |
| `--plugins`      | no          | Wipe and recopy `plugins/`. |

Parsing rules:

- Arguments are scanned left to right. Unknown tokens are ignored silently. A value flag at the very end with no value is ignored.
- Repeated value flags: the last occurrence wins.
- Sync-type flags are additive; `--all` implies the other three.
- Validation (each failure is `error(...)`, i.e. `IllegalStateException`, see §6):
  - `--ide` missing: `--ide <ide-path> is required`
  - neither `--path` nor `--all-projects`: `Either --path or --all-projects is required`
  - both given: `Cannot specify both --path and --all-projects`
- No sync-type flag at all is accepted and results in a sync that copies nothing (it still stops and restarts the IDE if running).

## 4. Execution

1. `KoinInit.init()` with no extra modules (core modules only, core-spec §5).
2. Parse arguments.
3. Build `IdeInstallation(idePath, "IDE")`. The display name is fixed to `"IDE"`; only `executablePath` matters for `directoryName`, so this resolves the same `<ideDir>` as the plugin's `currentIdeConfigRepository`.
4. `IdeRegistry.getInstance().getRepository(installation)` -> `IdeConfigRepository` rooted at `<base>/v2/<ideDir>`. `<base>` is `-Dproject.juggler.base.dir` if set in *this* JVM, else `~/.project-juggler`. Nothing is written to `config.json` by the helper itself.
5. `ProjectLauncher.getInstance(repository)`.
6. Target selection:
   - `--path`: `ProjectManager.resolvePath(raw)` then `ProjectManager.get(path)`; a missing `metadata.json` is `error("Project not found: <raw>")`.
   - `--all-projects`: `ProjectManager.listSyncable()` (all tracked projects minus the main project); an empty list is `error("No tracked projects found")`. Directory order is whatever `Files.list` returns (unspecified).
7. For each target, sequentially: `syncProject(project, syncVmOptions, syncConfig, syncPlugins, SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60))`. `onProgress` is the no-op default, so the helper prints no progress of its own; the only output is what core prints (`IntelliJLauncher`, `ProcessLauncherImpl`).
8. `main` returns. No explicit `exitProcess`; the JVM ends when non-daemon threads finish (core-spec §7.3: the launcher's 10 s crash-watch thread keeps it alive up to 10 s after the last restart).

Per-project behavior is exactly core-spec §8 with both booleans set. In particular, the IDE is only restarted if `isProjectOpen` was true when the helper looked (`config/.lock` present).

## 5. Plugin contract (`BundledSyncHelper`, `Sync.kt`)

### 5.1 Bundling

`plugin/build.gradle.kts` makes `processResources` depend on `:sync-helper:installDist` and copies `sync-helper/build/install/sync-helper/` into plugin resources under `sync-helper/`, so the plugin jar contains `sync-helper/bin/*` and `sync-helper/lib/*.jar`.

### 5.2 Extraction (`BundledSyncHelper.getExecutable()`)

- First call per IDE process: create `Files.createTempDirectory("sync-helper-")`, extract `sync-helper/bin` and `sync-helper/lib` from the plugin's classloader into it. Jar resources are walked by enumerating all jar entries with the prefix; file-system resources (dev mode / `runIde`) are copied with `Files.walk`.
- Executable is `bin/sync-helper.bat` on Windows, `bin/sync-helper` otherwise; its executable bit is set on Unix (failure only logs a warning).
- Missing resource or executable throws `IllegalStateException`.
- The resolved path is cached in a static field for the life of the IDE process and reused as long as the file still exists. The temp directory is never deleted.

### 5.3 Decision to use the helper

`isCurrentProject(repo, path)` is true iff the running IDE is isolated (its `idea.config.path` contains `.../projects/<id>/config`) and `<id>` equals the id of `ProjectManager.get(path)`. Callers:

- `SyncSettingsSubAction` (Recent Projects popup, sync one project): if the target is the current project, spawn the helper with `--ide <currentIde.executablePath> --path <pathString>`; otherwise sync in-process.
- `SyncAllProjectsAction`: if any target is the current project, spawn with `--ide <currentIde.executablePath> --all-projects`; otherwise in-process.

The sync-type flag is appended from `SyncType`: `All -> --all`, `VmOptions -> --vmoptions`, `Config -> --config`, `Plugins -> --plugins`. Exactly one is passed.

### 5.4 Spawn and self-exit (`performSelfShutdownSync`)

1. Show an info notification ("IntelliJ will close to sync ... and reopen automatically...").
2. `BundledSyncHelper.getExecutable()`.
3. `ProcessBuilder(exe, *args).inheritIO().start()`: the helper inherits the IDE's environment (including `IDEA_VM_OPTIONS` pointing at the isolated vmoptions file) and its stdio.
4. `Thread.sleep(100)`.
5. `invokeLater { Application.exit() }`.
6. Any `Exception` shows an error notification "Failed to initiate self-shutdown sync: ..." and the IDE keeps running.

The IDE's own exit removes `config/.lock`. The helper, started ~100 ms earlier, sees the lock (if the IDE has not finished exiting yet), writes `signals/stop-request.json`, which `ShutdownSignalService` may also act on, and then waits for the lock to disappear before syncing and relaunching.

## 6. Exit codes and errors

- Successful run: exit 0 after all targets are synced (and the JVM's non-daemon threads finish).
- Any uncaught exception (argument validation, `Project not found`, `SyncException` from a held lock or shutdown timeout, `IllegalStateException` for a missing base path, `IOException` during copying) terminates the JVM with exit code 1 and a stack trace on stderr. There is no error handling in `main`.
- With `--all-projects`, a failure on one project stops the loop; later projects are not synced. The failing project's `signals/` dir and sync lock are still cleaned by `doWithLock`'s finally block; already-synced projects are left synced.
- Nobody reads the exit code: the plugin does not keep the `Process` handle, and the IDE that would show a notification has exited.

## 7. Environment and portability

- The start script resolves Java via `JAVA_HOME`, then `java` on `PATH`. The IDE's bundled JBR is not used.
- `-Dproject.juggler.base.dir` is **not** forwarded by the plugin, so the helper always uses `~/.project-juggler` unless the environment sets `JAVA_OPTS`/`SYNC_HELPER_OPTS` (standard `application` script hooks).
- Runs on macOS, Linux and Windows wherever `core` does; it has no platform-specific code of its own.

## Known gaps

1. **Restart race.** If the IDE finishes exiting before the helper's first `isProjectOpen` check (the plugin only waits 100 ms, and JVM startup of the helper is slower than that on most machines), `wasRunning` is false, the sync runs, and the IDE is *not* restarted despite the notification promising it. Conversely, if the IDE is still running, both the plugin's `Application.exit()` and the helper's stop request try to close it.
2. `project.juggler.base.dir` is not propagated from the IDE to the helper. Under `runIde` the plugin uses `~/.project-juggler-test` while the helper syncs `~/.project-juggler`, so self-sync in the sandbox operates on the wrong tree (and will fail with `Project not found`).
3. `--path <main project>` fails with `SyncException` from core (core-spec §8 step 0); the helper has no special handling and exits 1.
4. Stdout/stderr are inherited from the IDE and disappear with it. Errors (lock held, shutdown timeout, missing base paths) are invisible to the user; the IDE simply does not come back.
5. No `exitProcess`: the helper lingers up to 10 s after the last relaunch because of the non-daemon crash-watch thread. One sync-type-less invocation (`--ide X --path Y` with no type flag) is accepted and does a stop/restart with no copying.
6. Unknown arguments are ignored, so a typo like `--plugin` silently produces an empty sync.
7. `--all-projects` is not transactional and has unspecified order; the current project may be restarted before the others are synced, and a failure midway leaves the set half-synced with no report.
8. The helper requires a system `java`. On a machine with only the IDE's JBR, extraction succeeds but the script fails at startup, again invisibly.
9. Each IDE process extracts a fresh `sync-helper-*` temp directory that is never removed.
10. `IdeInstallation(idePath, "IDE")` is correct for directory resolution but any code path that saves `config.json` through this repository would persist the placeholder display name.
11. `.github/workflows/release.yml` still references a `:cli` module; there is no release artifact for `sync-helper` outside the plugin zip.
12. Untested: argument parsing, target selection, and the extraction logic in `BundledSyncHelper`.
