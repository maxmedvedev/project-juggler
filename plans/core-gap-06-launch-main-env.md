# Clear IDEA_VM_OPTIONS when launching the main project without base vmoptions (core-spec gap 6)

## Context

`docs/core-spec.md`, Known gaps 6: "`launchMain` inherits the parent's `IDEA_VM_OPTIONS` when no base vmoptions is
configured, so a main project launched from an isolated IDE may get isolated options."

`IntelliJLauncher.launchMain` (`core/src/main/kotlin/com/projectjuggler/process/IntelliJLauncher.kt`) passes
`emptyMap()` to `ProcessLauncher.launch` when `baseVmOptionsPath` is null. `ProcessLauncherImpl` only does
`environment().putAll(env)` on top of the inherited environment, so nothing removes a variable. An isolated IDE runs
with `IDEA_VM_OPTIONS=<projRoot>/idea.vmoptions`; opening the main project from it (Recent Projects popup) starts the
main IDE with the isolated `-Didea.config.path=…` etc., i.e. a second isolated instance of the same project instead of
the base one.

Decision (made with the user, 2026-10-30): make the environment parameter nullable-valued,
`Map<String, String?>`, where a `null` value means "remove this variable from the child environment". This keeps the
interface small and lets `launchMain` state its intent in one line.

Related plans: `plans/core-gap-04-vmoptions-refresh.md` and `plans/core-gap-05-debug-port-policy.md` edit
`IntelliJLauncher.launch` (not `launchMain`); `plans/core-gap-13-process-launcher.md` (if present) rewrites the body of
`ProcessLauncherImpl.launch`. Independent; the env-application helper from this plan is called from whichever builder
code ends up there.

## Target behavior

| `baseVmOptionsPath` | Parent env has `IDEA_VM_OPTIONS` | Child env |
|---|---|---|
| configured and exists | any | `IDEA_VM_OPTIONS=<base vmoptions>` |
| unset or missing | set (isolated IDE) | variable absent |
| unset or missing | absent | variable absent (no change) |
| isolated launch (`launch`) | any | `IDEA_VM_OPTIONS=<projRoot>/idea.vmoptions` (unchanged) |

## Implementation

- `process/ProcessLauncher.kt`:
  - Interface: `environment: Map<String, String?> = emptyMap()`. KDoc: a `null` value removes the variable from the
    inherited environment.
  - `ProcessLauncherImpl`: add `private fun ProcessBuilder.applyEnvironment(environment: Map<String, String?>)` that
    does `environment().remove(key)` for null values and `put` otherwise; call it from `createWindowsProcess` and
    `createUnixProcess` instead of the two `putAll` calls. The diagnostics print stays (`null` prints as "null";
    acceptable, or print `key=<unset>` via `mapValues`).
- `process/IntelliJLauncher.kt`, `launchMain`:
  ```kotlin
  val environment = mapOf("IDEA_VM_OPTIONS" to baseVmOptionsPath?.toString())
  processLauncher.launch(intellijPath, listOf(projectPath.toString()), environment, onFailure)
  ```
  Keep the status `println` branches. `launch()` is unchanged (its map is `Map<String, String>`, which is a subtype).
- Callers outside core: none (`plugin/` and `sync-helper/` do not reference `ProcessLauncher`; verified 2026-10-30
  with `grep -rn ProcessLauncher plugin/src sync-helper/src`).
- Tests that stub the interface: `core/src/test/.../core/LaunchProjectTest.kt` captures `slot<Map<String, String>>()`
  on the env parameter; change to `slot<Map<String, String?>>()`. `FileBasedProjectTest.kt` and
  `process/ProjectLauncherSyncTest.kt` use `any()` only and keep compiling.

## Tests

Kotest `StringSpec` + MockK in `core/src/test/kotlin/com/projectjuggler/`.

- New `process/IntelliJLauncherTest.kt` with `KoinTestExtension()`, a `mockk<ProcessLauncher>(relaxed = true)` bound
  via `loadKoinModules(module { single<ProcessLauncher> { mock } })` as in `LaunchProjectTest`, and an
  `IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))`:
  - "launchMain removes IDEA_VM_OPTIONS when no base vmoptions is configured": capture the env slot, call
    `IntelliJLauncher.getInstance(repo).launchMain(projectDir)`, assert the map contains key `IDEA_VM_OPTIONS` with
    value `null`.
  - "launchMain passes the base vmoptions file when configured": save `IdeConfig(baseVmOptionsPath = file)`, assert
    the value equals the file path.
  - "launchMain does not create isolated directories or metadata": `repo.baseDir.resolve("projects")` does not exist
    afterwards.
- New `process/ProcessLauncherImplTest.kt` (Unix only, Kotest `enabledIf = { !Platform.isWindows() }` or `xif`), or
  extend the file if `plans/core-gap-13-process-launcher.md` already created it: launch `/usr/bin/env` with
  `mapOf("PJ_TEST_SET" to "1", "PATH" to null)` and no `onFailure`; this plan cannot read the child's output under
  inherited IO, so instead unit-test the helper: make `applyEnvironment` `internal`, build a `ProcessBuilder`, apply
  `mapOf("A" to "1", "PATH" to null)`, assert `environment()["A"] == "1"` and `"PATH" !in environment()`.

Manual check (cannot be automated): from an isolated IDE started by project-juggler, open the main project via the
Recent Projects popup; the new IDE must use the base config (title bar / Help → About shows the base config path, and
no new `projects/<id>` directory appears for it).

## Docs

`docs/core-spec.md`:
- §7.2: "`launchMain`: start the executable with the project path and `IDEA_VM_OPTIONS` set to the base vmoptions file
  when one is configured and exists, otherwise removed from the child environment."
- §7.3: "Extra env is merged over the inherited environment; a `null` value removes the variable."
- Known gaps: delete item 6.

## Sessions

1. Single session, Fable 5.1 at default effort, ~10% context. Reads: this plan, `CLAUDE.md`, `ProcessLauncher.kt`,
   `IntelliJLauncher.kt`, `LaunchProjectTest.kt` (for the Koin/MockK pattern), `di/KoinTestExtension.kt`. Implements
   everything above, runs Verification, does Finishing. Done when `:core:test` and `:plugin:compileKotlin` pass and
   gap 6 is gone from the spec.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:compileKotlin`
3. `./gradlew :sync-helper:build`
4. Manual check from the Tests section via `./gradlew :plugin:runIde` (set a main project first).

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-06-launch-main-env.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `drop inherited IDEA_VM_OPTIONS when launching the main project without base vmoptions`.
