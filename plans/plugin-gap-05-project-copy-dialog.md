# Replace the Open a Project Copy choosers with one validating dialog (plugin-spec gap 5)

## Context

`docs/plugin-spec.md`, Known gaps 5: "`OpenProjectCopyAction` uses a folder chooser for the destination, which can only
select existing directories, while the validation requires the destination not to exist. The user must type a new
path into the chooser for the flow to succeed. Validation and git checks run on EDT."

**Depends on `plans/plugin-tests.md` landing first.** This plan is written against the layout that plan introduces
(section H): `plugin/src/main/kotlin/com/projectjuggler/plugin/services/OpenProjectCopy.kt` with
`validateProjectCopy(source, destination): String?` and `createAndLaunchProjectCopy(repository, source, destination,
branchName, notificationHandler, launcher)`, and `OpenProjectCopyAction` that keeps two
`FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor())` calls, a
`Messages.showInputDialog` for the branch, `KoinInit.init` + `resolvePath` + `validateProjectCopy` on EDT, then a pooled
thread. Its `services/OpenProjectCopyTest` pins `validateProjectCopy`; this plan replaces that function and flips the
tests. If plugin-tests deviated (see its "Done in" notes), adapt names, not behavior.

Today, after plugin-tests: three modal steps (source chooser, destination chooser, branch input). The destination
chooser cannot return a non-existent folder, yet `notification.error.destination.exists` rejects existing ones, so the
only working path is typing a new name into the chooser's path field. `resolvePath`, `isDirectory`,
`GitWorktreeManager.isGitRepository` (a `.git` existence stat, no git process) and `KoinInit.init` run on EDT; errors
arrive as balloons after all three steps.

Decision (made with the user, 2026-10-30): **one `DialogWrapper`** with three rows (source, branch, destination); the
destination is a free text path pre-filled with `<source parent>/<source name>-<branch>` until the user edits it, and
its browse button picks a *parent* folder and appends the current destination folder name, because it is the only shape
that makes a not-yet-existing destination selectable and shows errors next to the field. Rejected: keeping the separate
steps with a parent-folder chooser plus a name input (four modal steps, balloon errors), and accepting an existing empty
folder (needs a core change in `GitWorktreeManager.createWorktree`).

Decision (made with the user, 2026-10-30): `doValidate` (on OK) runs the pure `validateProjectCopyInput` on EDT, i.e.
string checks plus at most three local `Files` stats and never a git process, and the pooled thread re-validates before
creating the worktree (authoritative, closes the race), because the stats are cheap and inline errors spare the user
from re-entering everything. Rejected: blank checks only in the dialog with all file checks on the pooled thread (no EDT
I/O, but errors as balloons after the dialog closed). The spec keeps one sentence saying the stats run on EDT by design.

Related: `plans/core-gap-11-git-helpers.md` changes the `IOException` text `createWorktree` throws (shown through
`notification.error.worktree.failed`); independent. Plugin gap 4 (two identical catch blocks) is already resolved by
plugin-tests section H. Overlaps with sibling plans (whichever lands second merges):
`plans/plugin-gap-14-edt-io-and-koin-init.md` moves the same post-dialog block of `OpenProjectCopyAction` to a pooled
thread and deletes its `KoinInit.init`; if gap 14 landed first, put `openProjectCopy` in the existing pooled thread and
do not add a `KoinInit.init` back; `plans/plugin-gap-03-bundle-strings-and-catches.md` replaces `"Unknown error"`
fallbacks with a `userMessage()` helper in `Utils.kt` and edits the `OpenProjectCopyTest` failure cases; use the helper
in `openProjectCopy` if present; `plans/plugin-gap-09-ide-path-drift.md` adds `PathUtils.isSamePath` next to this plan's
`PathUtils.resolveAbsolute` (no conflict); `plans/plugin-gap-15-settings-and-health-check.md` normalizes settings paths
with `PathUtils.expandTilde` + `toAbsolutePath().normalize()`, which is exactly `resolveAbsolute`; whichever lands
second switches it to the shared helper. Out of scope: the launch after creation still skips the main-project prompt and
focus check (spec §8).

## Target behavior

- Action invoked: `OpenProjectCopyDialog(project)` opens with all three fields empty. No Koin, no I/O.
- Source chosen (browse, `singleDir`) or typed, branch typed: the destination text follows `suggestDestination(source,
  branch)` while the user has not edited it.
- Destination browse: folder chooser (`file.chooser.destination.*`, text says "parent folder"); the result is
  `<chosen>/<current destination name or suggested name>`.
- OK with blank source / branch / destination: inline error on that field (`dialog.project.copy.error.blank.*`); the
  dialog stays open.
- OK with an unparsable path: inline `dialog.project.copy.error.invalid.path` on that field.
- OK, source not a directory / not a git repo: inline `notification.error.not.directory` /
  `notification.error.not.git.repository` on source.
- OK, destination exists: inline `notification.error.destination.exists` on destination.
- OK, valid: the dialog closes. Pooled thread: `KoinInit.init`, re-validate (failure -> error balloon, stop),
  `notification.info.worktree.started`, then `createAndLaunchProjectCopy` exactly as after plugin-tests.
- Cancel: nothing.

Paths are resolved the same way as `ProjectManager.resolvePath` (tilde expansion, absolute, normalized), through one
shared helper (see Implementation).

## Implementation

### Core: share path resolution (`core/src/main/kotlin/com/projectjuggler/util/PathUtils.kt`, `core/ProjectManager.kt`)

- Add `fun resolveAbsolute(rawPath: String): Path = expandTilde(Path.of(rawPath)).toAbsolutePath().normalize()` to
  `PathUtils` (throws `InvalidPathException` like `Path.of`).
- `ProjectManager.resolvePath(rawPath)` becomes `ProjectPath(PathUtils.resolveAbsolute(rawPath).toString())`. No
  behavior change; `ProjectManagerTest` "should expand tilde in path strings" keeps passing.

### `plugin/.../services/OpenProjectCopy.kt` (from plugin-tests H)

Replace `validateProjectCopy(source, destination): String?` with:

```kotlin
internal enum class ProjectCopyField { SOURCE, BRANCH, DESTINATION }
/** Raw dialog text, untrimmed. */
internal data class ProjectCopyInput(val source: String, val branchName: String, val destination: String)
internal data class ProjectCopyRequest(val source: ProjectPath, val destination: ProjectPath, val branchName: String)
internal sealed interface ProjectCopyValidation {
    data class Valid(val request: ProjectCopyRequest) : ProjectCopyValidation
    data class Invalid(val field: ProjectCopyField, val message: String) : ProjectCopyValidation
}
/** Pure apart from three local stats; no git process. Checks in field order: source, branch, destination. */
internal fun validateProjectCopyInput(input: ProjectCopyInput): ProjectCopyValidation
/** `<source parent>/<source name>-<branch with '/' replaced by '-'>`; null when source or branch is blank/invalid. */
internal fun suggestDestination(source: String, branchName: String): String?
```

- Blank checks use `isBlank()`; values are `trim()`med. Path parsing via `PathUtils.resolveAbsolute`, catching
  `InvalidPathException` -> `Invalid(field, dialog.project.copy.error.invalid.path)`.
- File checks and messages are the existing ones: `isDirectory()` -> `notification.error.not.directory`,
  `GitWorktreeManager.isGitRepository` -> `notification.error.not.git.repository`, `exists()` ->
  `notification.error.destination.exists`, each formatted with the trimmed raw text.
- `createAndLaunchProjectCopy` takes a `ProjectCopyRequest` instead of three parameters; body unchanged.

### New `plugin/.../actions/OpenProjectCopyDialog.kt`

`internal class OpenProjectCopyDialog(project: Project?) : DialogWrapper(project, true)`; title
`dialog.project.copy.title`, OK text `dialog.project.copy.ok`. Fields:

- `sourceField = TextFieldWithBrowseButton().apply { addBrowseFolderListener(project,
  FileChooserDescriptorFactory.singleDir().withTitle(source.title).withDescription(source.description)) }`.
- `branchField = JBTextField()`.
- `destinationField = TextFieldWithBrowseButton().apply { addBrowseFolderListener(ParentFolderListener(...)) }`
  where `ParentFolderListener : TextBrowseFolderListener(descriptor, project)` overrides
  `chosenFileToResultingText(chosenFile)` to return `Path.of(chosenFile.path).resolve(name).toString()`, `name` being
  the current destination's file name or the suggested one.
- A `DocumentAdapter` on source and branch text updates `destinationField.text` from `suggestDestination` while
  `destinationEdited` is false; a listener on the destination text field sets `destinationEdited = true` on user edits
  (guard with a `updatingDestination` flag so programmatic updates do not count).
- `createCenterPanel() = panel { row(label) { cell(sourceField).align(AlignX.FILL) } ... }` with
  `rowComment(dialog.project.copy.destination.comment)` under destination. `getPreferredFocusedComponent() =
  sourceField`.
- `override fun doValidate(): ValidationInfo?` runs `validateProjectCopyInput`; maps `Invalid.field` to the component
  (`sourceField.textField`, `branchField`, `destinationField.textField`). No `initValidation()`, so it runs on OK only.
- `val input: ProjectCopyInput` (current text of the three fields), read after `showAndGet()` returns true.

Platform APIs (IntelliJ 2025.1.1, `lib/app-client.jar` unless noted, in
`~/.gradle/caches/9.2.1/transforms/8403799d391fc2cc745f50c5e712a457/transformed/ideaIU-2025.1.1-aarch64/lib/`); all
stable in 251 unless noted:

- `com.intellij.openapi.ui.DialogWrapper` (`doValidate`, `createCenterPanel`, `showAndGet`, `init`).
- `com.intellij.ui.dsl.builder.BuilderKt.panel`, `Panel.row(String, ...)`, `Row.cell(T)`, `Cell.align`.
- `com.intellij.openapi.ui.TextFieldWithBrowseButton.addBrowseFolderListener(Project, FileChooserDescriptor)` and
  `addBrowseFolderListener(TextBrowseFolderListener)`; the `(String, String, Project, descriptor)` overload is
  `@Deprecated`, do not use it.
- `com.intellij.openapi.ui.TextBrowseFolderListener(FileChooserDescriptor, Project)`, protected
  `chosenFileToResultingText(VirtualFile)` (from `BrowseFolderRunnable`).
- `FileChooserDescriptorFactory.singleDir()`, `FileChooserDescriptor.withTitle/withDescription`.
  `createSingleFolderDescriptor()` is `@ApiStatus.Obsolete`; replace it here.
- Avoid `Row.textFieldWithBrowseButton(descriptor, project, fileChosen)`: it is `@ApiStatus.Experimental` in 251.
- `com.intellij.openapi.ui.ValidationInfo(String, JComponent)`, `com.intellij.ui.DocumentAdapter`,
  `com.intellij.ui.components.JBTextField`.

### `plugin/.../actions/OpenProjectCopyAction.kt`

```kotlin
val dialog = OpenProjectCopyDialog(project)
if (!dialog.showAndGet()) return
val input = dialog.input
application.executeOnPooledThread { openProjectCopy(project, input) }
```

`openProjectCopy` lives in `services/OpenProjectCopy.kt` (logic out of the action): `KoinInit.init(pluginModule)`,
repository from `IdeInstallationService.currentIdeConfigRepository`, `validateProjectCopyInput` again (`Invalid` ->
error notification, return), `notification.info.worktree.started`, `createAndLaunchProjectCopy(...)`. The catch rule
from `CLAUDE.md` applies to the whole body (catch `Throwable`, rethrow `ControlFlowException`, log,
`notification.error.worktree.failed`). Drop the `FileChooser`, `Messages` and `isDirectory`/`exists` imports.

### Bundle (`plugin/src/main/resources/messages/ProjectJugglerBundle.properties`)

- Add `dialog.project.copy.title=Open a Project Copy`, `dialog.project.copy.ok=Create and Open`,
  `dialog.project.copy.source.label=Source repository:`, `dialog.project.copy.branch.label=New branch:`,
  `dialog.project.copy.destination.label=Worktree folder:`,
  `dialog.project.copy.destination.comment=Must not exist yet; git creates it.`,
  `dialog.project.copy.error.blank.source=Choose the source repository`,
  `dialog.project.copy.error.blank.branch=Enter a branch name`,
  `dialog.project.copy.error.blank.destination=Enter the worktree folder`,
  `dialog.project.copy.error.invalid.path=Not a valid path: {0}`.
- Change `file.chooser.destination.description` to "Choose the folder to create the worktree in".
- Delete `input.dialog.branch.title` and `input.dialog.branch.message` (grep first; no other user).

## Tests

`plugin/src/test/kotlin/com/projectjuggler/plugin/services/OpenProjectCopyTest.kt` (from plugin-tests; flip and
extend). Kotest `StringSpec`, `KoinTestExtension()`, `createTempDir`; messages compared via
`ProjectJugglerBundle.message`.

- Replace the four `validateProjectCopy` cases with `validateProjectCopyInput` cases: blank source / branch /
  destination -> `Invalid` with the right field and blank message; source is a file -> `SOURCE`, `not.directory`;
  directory without `.git` -> `SOURCE`, `not.git.repository`; existing destination -> `DESTINATION`,
  `destination.exists`; `"\u0000"` as destination -> `DESTINATION`, `invalid.path`; valid -> `Valid` with
  normalized absolute paths and trimmed branch; `"~/x"` source resolves under `user.home` (skip on Windows).
- `suggestDestination`: `/a/repo` + `feature/x` -> `/a/repo-feature-x`; blank branch -> null; blank source -> null.
- `createAndLaunchProjectCopy` cases keep their assertions with a `ProjectCopyRequest` argument.
- New `openProjectCopy` case: invalid input on the pooled-thread re-check -> one error, no `createWorktree` call
  (`mockkObject(GitWorktreeManager)`), no "started" info. Needs `IdeInstallationService`; if that pulls the test
  application in, give `openProjectCopy` a `repository` default parameter and pass `testRepository(...)` instead.

`core/src/test/kotlin/com/projectjuggler/util/PathUtilsTest.kt` (create if absent): `resolveAbsolute("a/../b")` ->
absolute and normalized; `"~"` -> `user.home` on Unix.

Not automated: the dialog itself (Swing). Manual check: `./gradlew :plugin:runIde`, File > Open a Project Copy; pick a
git repo, type `feature/x`, see destination suggested as `<repo>-feature-x`; browse destination to `/tmp`, see
`/tmp/<repo>-feature-x`; point destination at an existing folder and press OK, see the inline error; fix it and confirm
the worktree is created and an isolated IDE opens.

## Docs

`docs/plugin-spec.md`:

- §8: rewrite steps 1-2 as: one `OpenProjectCopyDialog` (source with folder browse, branch, destination pre-filled as
  `<source>-<branch>`, browse picks the parent folder); inline validation on OK via `validateProjectCopyInput`
  (blank fields, path syntax, source is a git directory, destination absent; local stats only, no git process, on EDT);
  then on a pooled thread `openProjectCopy` re-validates, posts "started", creates the worktree and launches.
- §12 threading table: "Open a Project Copy dialog validation (local stats)" -> EDT; "worktree validation,
  creation, launch, Koin init" -> pooled thread.
- Known gaps: delete item 5 and renumber nothing (keep numbers stable; other plans reference them by number).
- If plugin-tests added a §13 tests table, update the `OpenProjectCopyTest` row.

`docs/core-spec.md` §11 (utilities): add `PathUtils.resolveAbsolute` (tilde, absolute, normalize; used by
`ProjectManager.resolvePath`).

## Sessions

1. Single session, Fable 5.1 at default effort, ~20% context. Reads: this plan, `CLAUDE.md`,
   `actions/OpenProjectCopyAction.kt`, `services/OpenProjectCopy.kt`, `services/OpenProjectCopyTest.kt`,
   the worktree keys in
   `plugin/src/main/resources/messages/ProjectJugglerBundle.properties`, `MainProjectPromptService.kt` (existing
   dialog style), core `PathUtils.kt`, `ProjectManager.resolvePath`, spec §8 and §12. Implements everything above,
   runs Verification, does Finishing. Done when `:core:test` and `:plugin:test` pass and the manual check succeeds.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.OpenProjectCopyTest"`, then
   `./gradlew :plugin:test`
3. `./gradlew :plugin:verifyPlugin` (no new experimental/obsolete API usages reported for the touched files)
4. `./gradlew build`
5. Manual check from the Tests section.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-05-project-copy-dialog.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path (never `git add -A`/`-a`). Commit in the style of
  recent commits (lowercase imperative subject, body explaining the dialog and why the chooser could not work, ending
  with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `ask for project copy inputs in one validating dialog`. List anything left uncommitted in the reply.
