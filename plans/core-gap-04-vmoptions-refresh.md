# Refresh isolated vmoptions when the base file changes (core-spec gap 4)

## Context

`docs/core-spec.md`, Known gaps 4: "Changed base vmoptions only update the hash on launch; the file is refreshed only
by a vmoptions sync. Update mode never adds `-Dproject.juggler.base.dir` to a file lacking it."

Today, in `core/src/main/kotlin/com/projectjuggler/process/ProjectLauncher.kt`, `launch()` calls
`BaseVMOptionsTracker.hasChanged()` and, if true, only `updateHash()`. `IntelliJLauncher.launch()` then calls
`VMOptionsGenerator.generate(base, dirs, port)` without `forceRegenerate`, so for an existing `idea.vmoptions` only the
marker block is rewritten (`updateOverrides`). Example: edit `-Xmx` in the base `.vmoptions`, relaunch an isolated
project; its `idea.vmoptions` keeps the old `-Xmx` forever, and the IDE-level hash is now "current", so no later
launch notices either. A second defect in `updateOverrides`: the replaced run stops at lines starting with `-Didea.`,
so a file written before the base-dir line existed never gains `-Dproject.juggler.base.dir`.

Decisions (made with the user, 2026-10-30):
- Track the base hash **per project** in `ProjectMetadata` (`baseVmOptionsHash`), so each project regenerates on its
  own next launch. The IDE-level `IdeConfig.baseVmOptionsHash` / `BaseVMOptionsTracker` stay as they are (used by
  `IntegrationTest` and the plugin for change notifications).
- Regenerate inside `IntelliJLauncher.launch` (the only place that knows dirs, port and base path), not in
  `ProjectLauncher.launch`.

Related plans: `plans/core-gap-05-debug-port-policy.md` also edits `IntelliJLauncher.launch` and
`ProjectLauncher.doSync` (the `ensureDebugPort` call), and `plans/core-gap-06-launch-main-env.md` edits
`IntelliJLauncher.launchMain`. The changes are independent; if both land, keep both edits in the same functions (this
plan changes the `generate(...)` call and adds hash handling; gap 5 only changes how `debugPort` is obtained).

## Target behavior

| Case | Result |
|---|---|
| First launch of a project (no `idea.vmoptions`) | Fresh write as today; `metadata.baseVmOptionsHash` = SHA-256 of base file (null when no base) |
| Relaunch, base file unchanged | Update mode (marker block only); hash unchanged |
| Relaunch, base file changed since the project's last write | `generate(..., forceRegenerate = true)`; hash updated in `metadata.json` |
| Relaunch, base path unset or missing, project hash non-null | Treated as changed: regenerate without base, hash set to null |
| Relaunch, base unset and hash null | Update mode |
| vmoptions sync (`doSync`) | Already forces regeneration; now also persists the hash |
| Update mode on a file without `-Dproject.juggler.base.dir` | Marker block is replaced by marker + 4 path lines + base-dir line |
| Update mode on a file with the base-dir line | Line is replaced in place, not duplicated; lines after the block are untouched |
| Metadata written by older versions (no `baseVmOptionsHash` key) | Deserializes as null → first launch regenerates once, then stable |

## Implementation

All in `core/`, package `com.projectjuggler`.

- `config/ProjectMetadata.kt`: add `val baseVmOptionsHash: String? = null` (last field; `encodeDefaults = false` in
  `IdeConfigRepository` keeps it out of JSON when null). KDoc: "SHA-256 of the base vmoptions used to write
  `idea.vmoptions`; null when written without a base".
- `core/ProjectManager.kt`:
  - `registerOrUpdate`: preserve `baseVmOptionsHash = existing?.baseVmOptionsHash` next to `debugPort`.
  - Add `fun updateBaseVmOptionsHash(project: ProjectMetadata, hash: String?): ProjectMetadata` that saves
    `project.copy(baseVmOptionsHash = hash)` through `ideConfigRepository.saveProjectMetadata` and returns it. Reload
    the latest metadata first (`ideConfigRepository.loadProjectMetadata(project.path) ?: project`) so a `debugPort`
    written by `ensureDebugPort` moments earlier is not lost.
- `core/VMOptionsGenerator.kt` (object):
  - `const val OVERRIDES_MARKER = "# project-juggler overrides (auto-generated)"` replaces the four string literals.
  - `private fun overrideLines(dirs: ProjectDirectories): List<String>` = marker, the four `-Didea.*.path=` lines and
    `-D$PROJECT_JUGGLER_BASE_DIR=${IdeRegistry.getDefaultBaseDir()}`. The fresh path appends these lines; the update
    path uses the same list for both the "no marker" and "replace" branches (deduplicates the three copies).
  - `updateOverrides`: the end-of-block scan accepts `startsWith("-Didea.") || startsWith("-Dproject.juggler.")`.
  - Add `fun computeBaseHash(baseVmOptionsPath: Path?): String?` = `HashUtils.calculateFileHash(path)` when the path
    is non-null and exists, else null. (Public so launcher and sync share one definition.)
- `process/IntelliJLauncher.kt`, `launch()`:
  ```kotlin
  val baseHash = VMOptionsGenerator.computeBaseHash(baseVmOptionsPath)
  val baseChanged = baseHash != project.baseVmOptionsHash
  val vmOptionsFile =
      VMOptionsGenerator.generate(baseVmOptionsPath, projectDirs, debugPort, forceRegenerate = baseChanged)
  if (baseChanged) projectManager.updateBaseVmOptionsHash(project, baseHash)
  ```
  Use `ProjectManager.getInstance(ideConfigRepository)` once into a local instead of the current inline call.
- `process/ProjectLauncher.kt`, `doSync()` vmoptions branch: after `generate(..., forceRegenerate = true)`, call
  `projectManager.updateBaseVmOptionsHash(project, VMOptionsGenerator.computeBaseHash(baseVmOptionsPath))`. Leave the
  `hasChanged()/updateHash()` lines in `launch()` as they are.

## Tests

Kotest `StringSpec` + MockK in `core/src/test/kotlin/com/projectjuggler/`.

- `core/VMOptionsGeneratorTest.kt` (extend):
  - "update mode adds the base dir line when missing": write a file with user lines, the marker and four `-Didea.`
    lines; call `generate(null, dirs, null)`; assert exactly one `-Dproject.juggler.base.dir=` line, directly after the
    four path lines, and the user lines unchanged.
  - "update mode does not duplicate the base dir line": generate fresh, append a trailing `-Xmx1g` after the block,
    generate again in update mode; assert one base-dir line, trailing line kept.
  - "computeBaseHash returns null for missing path and the file hash otherwise".
- `core/LaunchProjectTest.kt` (extend, uses `KoinTestExtension` and the mocked `ProcessLauncher` as the existing
  test does): "relaunch regenerates vmoptions when the base file changed": launch, rewrite the base file with
  `-Xmx4096m`, launch again, assert the project's `idea.vmoptions` contains `-Xmx4096m` and
  `loadProjectMetadata(path)!!.baseVmOptionsHash == HashUtils.calculateFileHash(base)`. Second case: "relaunch keeps a
  user edit when the base file is unchanged" (append a line to `idea.vmoptions`, relaunch, line still present).
- `core/ProjectManagerTest.kt` (extend): "registerOrUpdate preserves baseVmOptionsHash"; "updateBaseVmOptionsHash
  keeps a debugPort saved after the project was loaded".
- `process/ProjectLauncherSyncTest.kt` (extend the existing vmoptions sync case): after the sync, metadata hash equals
  the base file hash.

Nothing here needs a manual check.

## Docs

`docs/core-spec.md`:
- §3.2: add `baseVmOptionsHash` to the example and the "omitted when null" sentence.
- §4 `ProjectManager` bullet: `registerOrUpdate` preserves `debugPort` and `baseVmOptionsHash`; mention
  `updateBaseVmOptionsHash`.
- §6: update-mode paragraph (block = marker + `-Didea.` + `-Dproject.juggler.` lines, replaced by marker + four paths
  + base-dir line); add `computeBaseHash`.
- §7.1 step 1 and step 4 `generate` bullet: per-project hash comparison, forced regeneration when it differs.
- §8 step 4 vmoptions bullet: hash persisted.
- Known gaps: delete item 4 (leave the other numbers; Markdown renumbers).

## Sessions

1. Single session, Fable 5.1 at default effort, ~15% context. Reads: this plan, `CLAUDE.md`, `VMOptionsGenerator.kt`,
   `IntelliJLauncher.kt`, `ProjectLauncher.kt` (lines 35–55 and 120–150), `ProjectManager.kt`, `ProjectMetadata.kt`,
   `VMOptionsGeneratorTest.kt`, `LaunchProjectTest.kt`. Implements all sections above, runs Verification, does
   Finishing. Done when `./gradlew :core:test` is green and the spec no longer lists gap 4.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:compileKotlin`
3. `./gradlew :sync-helper:build`
4. Manual (optional): `./gradlew :plugin:runIde`, open an isolated project, edit the base `.vmoptions`, relaunch the
   project from the Recent Projects popup, confirm `~/.project-juggler-test/v2/<ide>/projects/<id>/idea.vmoptions`
   carries the edit and `metadata.json` has `baseVmOptionsHash`.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change (no architecture change).
- Delete `plans/core-gap-04-vmoptions-refresh.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path (`core/...`, `docs/core-spec.md`, this plan).
  Commit like the recent history: lowercase imperative subject, body with what and why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Suggested subject:
  `regenerate isolated vmoptions when the base file changes`.
