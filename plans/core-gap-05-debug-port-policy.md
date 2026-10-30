# Allocate debug ports only when the base vmoptions uses JDWP (core-spec gap 5)

## Context

`docs/core-spec.md`, Known gaps 5: "Debug ports are allocated even without JDWP in base options; uniqueness is per IDE,
not global; the documented lower bound (5000) differs from the code (5001)."

`IntelliJLauncher.launch()` (`core/src/main/kotlin/com/projectjuggler/process/IntelliJLauncher.kt`) and
`ProjectLauncher.doSync()` call `ProjectManager.ensureDebugPort(project)` unconditionally. The comment on the launcher
call even says "(if base VM options contains JDWP)". The port is only ever used by `VMOptionsGenerator.replaceJdwpPort`
on lines starting with `-agentlib:jdwp=`, so without such a line every project burns a port in `metadata.json` for
nothing. `PortAllocator`'s KDoc says `[5000, 15000)` while `MIN_PORT = 5001`; `ProjectMetadata.debugPort` says
`5000-15000`.

Decisions (made with the user, 2026-10-30):
- Allocate a port **only when the base vmoptions file contains a line starting with `-agentlib:jdwp=`**.
- Keep uniqueness per IDE installation (no global scan across `<base>/v2/*`).
- Ports already stored in `metadata.json` are kept and still passed to the generator; nothing is released.
- Fix the two comments to `[5001, 15000)`; remove the dead `Paths.get(...) ?: throw` elvis in `IntelliJLauncher`
  (`Paths.get` never returns null).

Related plans: `plans/core-gap-04-vmoptions-refresh.md` edits the same two call sites (the `generate(...)` call and
hash handling); `plans/core-gap-06-launch-main-env.md` edits `launchMain`. Independent; if both land, the `debugPort`
local from this plan feeds the `generate` call from gap 4.

## Target behavior

| Base vmoptions | Project has `debugPort` | Launch / vmoptions sync |
|---|---|---|
| contains `-agentlib:jdwp=…` | null | `ensureDebugPort` allocates the first free port in `[5001, 15000)` among this IDE's projects, saves it, JDWP address rewritten |
| contains `-agentlib:jdwp=…` | set | existing port reused |
| no JDWP line, unset or missing | null | no allocation; `generate` gets `null`; `metadata.json` has no `debugPort` |
| no JDWP line | set (from an earlier version) | port kept and passed through (harmless, no JDWP line to rewrite) |

## Implementation

- `core/VMOptionsGenerator.kt` (object): add
  `fun containsJdwp(baseVmOptionsPath: Path?): Boolean` = path non-null, exists, and any line `trim().startsWith(
  JDWP_PREFIX)`. Introduce `private const val JDWP_PREFIX = "-agentlib:jdwp="` and use it in `replaceJdwpPort` too.
- `core/ProjectManager.kt`: no logic change; keep `ensureDebugPort` as the single allocation point.
- `process/IntelliJLauncher.kt`, `launch()`:
  ```kotlin
  val debugPort = if (VMOptionsGenerator.containsJdwp(baseVmOptionsPath)) projectManager.ensureDebugPort(project)
                  else project.debugPort
  ```
  Remove the `?: throw IllegalStateException(...)` after both
  `Paths.get(ideConfigRepository.installation.executablePath)` calls (`launch` and `launchMain`); fix the step
  numbering comments.
- `process/ProjectLauncher.kt`, `doSync()` vmoptions branch: same conditional as above (extract a private helper in
  `ProjectLauncher` only if the expression is repeated inside that class; across the two classes the shared piece is
  `containsJdwp`).
- `core/PortAllocator.kt`: KDoc `[5001, 15000)` → better: reference `MIN_PORT`/`MAX_PORT` by name.
- `config/ProjectMetadata.kt`: comment `JDWP debug port (5001-14999), null when the base vmoptions has no JDWP agent`.

## Tests

Kotest `StringSpec` + MockK in `core/src/test/kotlin/com/projectjuggler/`.

- `core/VMOptionsGeneratorTest.kt` (extend): `containsJdwp` is false for null, missing file and a file without the
  agent line; true when a line (with leading spaces) starts with `-agentlib:jdwp=`.
- `core/LaunchProjectTest.kt` (extend; `KoinTestExtension`, mocked `ProcessLauncher` as in the existing test):
  - "does not allocate a debug port when base vmoptions has no JDWP": base `-Xmx2048m`, launch, assert
    `loadProjectMetadata(path)!!.debugPort == null`.
  - "allocates a debug port when base vmoptions has JDWP": base with
    `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`, launch, assert `debugPort == 5001` and
    `idea.vmoptions` contains `address=*:5001`.
- New `core/PortAllocatorTest.kt`: first port is 5001; skips ports used by other projects of the same repository
  (save two `ProjectMetadata` with 5001 and 5002, expect 5003); returns null when `MIN_PORT until MAX_PORT` is
  exhausted is impractical, skip that case.
- `process/ProjectLauncherSyncTest.kt` (extend the existing vmoptions sync case or add one): sync with a base without
  JDWP leaves `debugPort` null.

Nothing here needs a manual check.

## Docs

`docs/core-spec.md`:
- §3.2: `debugPort` wording (present only when JDWP is configured).
- §7.1 step 4 `ensureDebugPort` bullet: "only when the base vmoptions contains `-agentlib:jdwp=`; range 5001..14999,
  first port not used by another project of this IDE".
- §8 step 4 vmoptions bullet: same condition.
- §11 `PortAllocator` bullet unchanged (already 5001..14999); add `VMOptionsGenerator.containsJdwp` to §6.
- Known gaps: delete item 5. (Per-IDE uniqueness is now the documented, intended behavior, not a gap.)

## Sessions

1. Single session, Fable 5.1 at default effort, ~10% context. Reads: this plan, `CLAUDE.md`, `VMOptionsGenerator.kt`,
   `IntelliJLauncher.kt`, `ProjectLauncher.kt` lines 120–150, `PortAllocator.kt`, `ProjectManager.kt` lines 30–50,
   `LaunchProjectTest.kt`. Implements everything above, runs Verification, does Finishing. Done when `:core:test` is
   green and gap 5 is gone from the spec.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:compileKotlin`
3. `./gradlew :sync-helper:build`

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-05-debug-port-policy.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `allocate debug ports only when the base vmoptions enables JDWP`.
