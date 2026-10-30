# Detect isolated instances from Windows config paths (plugin-spec gap 2)

## Context

`docs/plugin-spec.md`, Known gaps 2: "`isCurrentProject` and `ShutdownSignalService.getCurrentProjectId` split the path
on `/`; on Windows (`\`) isolated detection fails, so self-shutdown sync and stop signals never trigger there."

**Depends on `plans/plugin-tests.md` landing first.** That plan replaces both copies of the parsing with
`IsolatedInstance.projectIdFromConfigPath(configPath: String): ProjectId?` in
`plugin/src/main/kotlin/com/projectjuggler/plugin/services/IsolatedInstance.kt` (section A), keeps the `/`-only split,
and pins it in `services/IsolatedInstanceTest` with `C:\\b\\projects\\x\\config -> null "(gap 2)"`. This plan is
written against that layout; if `IsolatedInstance` does not exist yet, stop and land plugin-tests first.

`VMOptionsGenerator` writes `-Didea.config.path=${projectDirectories.config}` (core), i.e. `Path.toString()`, so on
Windows the property is `C:\Users\u\.project-juggler\v2\<ideDir>\projects\<id>\config`. Splitting on `/` yields one
segment, no `projects` index, null. Consequences on Windows: `SyncPlanner` never picks `SelfShutdown` (the in-process
sync then stops the IDE it runs in and times out), and `ShutdownSignalService` never monitors, so every stop-and-sync
from another IDE times out after 60 s.

Decision (made with the user, 2026-10-30): split on both `/` and `\` and keep the "`projects/<id>/config` anywhere
in the path" rule, because the function stays pure and its Windows case is testable on macOS/Linux. Rejected: parsing
with `java.nio.file.Path` anchored at `<repo.baseDir>/projects/<id>/config` (Windows case only testable on Windows,
breaks when the IDE resolves another `<ideDir>` or base dir, see gap 10); an extra registered-project check (already
done by `isCurrentProject`/`loadProjectMetadata`).

Related: `plans/core-gap-14-main-instance-detection.md` adds `DirectoryManager.isIsolatedConfigDir(dir: Path)`
(Path-based, under `<ideDir>/projects`). It answers "is this any isolated dir", not "which id", and is not needed here.

## Target behavior

| `idea.config.path` | `projectIdFromConfigPath` |
|---|---|
| `/b/v2/ide/projects/p-1a2b/config` (and with trailing `/`) | `p-1a2b` (unchanged) |
| `C:\b\v2\ide\projects\p-1a2b\config` (and with trailing `\`) | `p-1a2b` (was null) |
| `C:/b/v2/ide/projects/p-1a2b/config`, mixed `C:\b/projects\p-1a2b/config` | `p-1a2b` |
| UNC `\\srv\share\projects\p-1a2b\config` | `p-1a2b` |
| `.../projects/x/system`, no `projects` segment | null (unchanged) |

Everything downstream (`isCurrentProject`, `SyncPlanner`, `ShutdownSignalService` start) follows without change.

## Implementation

`plugin/src/main/kotlin/com/projectjuggler/plugin/services/IsolatedInstance.kt`, `projectIdFromConfigPath`:

```kotlin
val parts = configPath.split('/', '\\').filter { it.isNotEmpty() }
```

instead of `split("/")`; the index logic is unchanged. Update the KDoc: drop the "(plugin-spec gap 2)" note, state that
both separators are accepted because the value is written by `Path.toString()` on the launching OS. No other file
changes; there is no other `split("/")` on paths in `plugin/src` (check with `grep -rn 'split("/")' plugin/src`).

## Tests

`plugin/src/test/kotlin/com/projectjuggler/plugin/services/IsolatedInstanceTest.kt` (from plugin-tests):

- Flip the "(gap 2)" case: rename to `"projectIdFromConfigPath accepts Windows separators"`, expect
  `ProjectId("x")` for `C:\\b\\projects\\x\\config`; remove the gap comment.
- Add: trailing backslash; forward-slash drive path `C:/b/projects/x/config`; mixed separators; UNC path; Windows path
  ending in `projects\\x\\system` -> null.
- `isCurrentProject` with `currentProjectId = IsolatedInstance.projectIdFromConfigPath(<windows-style string built from
  the registered project's id>)` -> true.

Manual check (needs Windows, optional): `./gradlew :plugin:runIde` on Windows, open an isolated project, sync it from
its own popup; the IDE must close and come back (self-shutdown path), and a sync from the main instance must stop it
within a few seconds instead of timing out.

## Docs

`docs/plugin-spec.md`:
- §6.3: "True when the `idea.config.path` system property, split on `/` and `\`, contains `projects/<id>/config` ..."
- §10: "parse `idea.config.path` exactly as §6.3" stays.
- §13 (added by plugin-tests): remove 2 from the list of gaps pinned by tests.
- Known gaps: delete item 2 (keep the numbering of the others as is; renumbering is not needed since plans refer to
  gaps by number).

`docs/sync-helper-spec.md` §5.3: "(its `idea.config.path` contains `.../projects/<id>/config` with either separator)".

## Sessions

1. Single session, Fable 5.1 at default effort, ~8% context. Reads: this plan, `CLAUDE.md`,
   `services/IsolatedInstance.kt`, `IsolatedInstanceTest.kt`, plugin-spec §6.3/§10/§13. Implements, tests, docs,
   Finishing.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.IsolatedInstanceTest"`
2. `./gradlew :plugin:test`
3. `./gradlew :plugin:compileKotlin`
4. Manual Windows check from Tests, if a Windows machine is at hand; otherwise say so in the commit body.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-02-windows-isolated-detection.md` in the same commit.
- `git status` / `git diff`; stage only `IsolatedInstance.kt`, `IsolatedInstanceTest.kt`, the two specs and this plan,
  by path. Commit in the style of recent commits (lowercase imperative subject, explanatory body, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `accept windows separators when detecting the isolated project id`. Report anything left uncommitted.
