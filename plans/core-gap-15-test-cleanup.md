# Remove the duplicated `PluginCopierTest`

Core-spec known gap 15, the part not owned by another gap plan.

## Context

Gap 15 lists untested areas and one duplicated test. The untested areas are each covered by the plan that fixes the
corresponding behavior:

| Item in gap 15 | Covered by |
|---|---|
| main-project branch of `isProjectOpen` | `plans/core-gap-09-running-detection.md` |
| `GitWorktreeManager` | `plans/core-gap-11-git-helpers.md` |
| `ProcessLauncherImpl` | `plans/core-gap-13-process-launcher.md` |
| `recent.json` trimming | `plans/core-gap-10-persistence-robustness.md` |
| vmoptions update path | `plans/core-gap-04-vmoptions-refresh.md` |
| `WindowFocuser` | nobody: it shells out to `osascript`/`wmctrl`/PowerShell and stays untested by decision |
| `PluginCopierTest` duplicates `DirectoryCopierTest` | this plan |

`core/src/test/kotlin/com/projectjuggler/util/PluginCopierTest.kt` has four `StringSpec` cases that call
`DirectoryCopier.copyIfFirstOpen` without exclusions: copy when the destination is empty, skip when it is not, skip
when the source is missing, copy nested directories.
`core/src/test/kotlin/com/projectjuggler/util/DirectoryCopierTest.kt` has the same four cases plus one for `copy`
deleting extra files, and uses the shared `createTempDir` helper instead
of hand-rolled `try/finally` cleanup. There is no `PluginCopier` class in main code; the file name is a leftover.

Decision (with the user): delete `PluginCopierTest`; `WindowFocuser` remains documented as untested.

## Target behavior

No behavior change. `./gradlew :core:test` runs one fewer spec; coverage of `DirectoryCopier.copyIfFirstOpen` is
unchanged because `DirectoryCopierTest` keeps the same four cases.

## Implementation

- `git rm core/src/test/kotlin/com/projectjuggler/util/PluginCopierTest.kt`.
- Before deleting, diff the two files once; if `PluginCopierTest` has an assertion `DirectoryCopierTest` lacks (it
  should not), move it into `DirectoryCopierTest` using `createTempDir`.

## Tests

None added. `DirectoryCopierTest` must still pass.

## Docs

`docs/core-spec.md`, Known gaps item 15: remove the sentence "`PluginCopierTest` duplicates `DirectoryCopierTest`."
Leave the "Untested: ..." list as is; each other gap plan removes its own entry from that list when it lands, and the
last one standing is `WindowFocuser`. If this plan is implemented after all of those, rewrite item 15 to:
"Untested: `WindowFocuser` (shells out to platform tools)."

## Sessions

1. Single session (Fable 5.1, default effort), ~4% context: read this plan and the two test files; delete, run the
   tests, update the spec, finish.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.util.DirectoryCopierTest"`
2. `./gradlew :core:test`

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-15-test-cleanup.md` in the same commit.
- Check `git status`; stage only the deleted test, the spec and this plan by path. Commit in the style of recent
  commits: lowercase imperative subject (e.g. `remove PluginCopierTest, a duplicate of DirectoryCopierTest`), a short
  body, and `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
