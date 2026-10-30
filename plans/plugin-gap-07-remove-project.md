# Plugin gap 7: confirm Remove Project, refuse while running, reshow after deletion, remove recents once

## Context

`docs/plugin-spec.md`, Known gaps 7: "Remove Project has no confirmation and deletes the whole isolated directory; the
popup is reshown before deletion finishes, so a project may still appear briefly. `removeRecentProject` removes from
recents twice (eagerly and inside `cleanProject`)."

**Depends on `plans/plugin-tests.md` landing first.** Written against what it introduces: `projectSubActions(item)` in
`actions/recent/RecentProjectPopup.kt`, `services/RemoveProjectTest` ("registered project: gone from `getRecent(10)`
and `<projRoot>` deleted; unknown path: no-op"), `RecentProjectPopupTest` (sub-action lists),
`RecordingNotificationHandler`, `markIsolatedInstanceOpen`, `registerProject`. No test there pins gap 7 by number.

Today (`actions/recent/RemoveProjectSubAction.kt`, `services/RemoveProject.kt`):
- `onChosen` starts `removeRecentProject` on a pooled thread and returns `step.doFinalStep {
  RecentProjectPopupBuilder(project).show() }`. The reshow races the deletion, and it calls `show()`, annotated
  `@RequiresBackgroundThread`, from the `doFinalStep` runnable on EDT (config and recents I/O on EDT).
- `removeRecentProject` calls `RecentProjectsIndex.remove(id)` and then `ProjectCleaner.cleanProject(metadata)`, which
  removes the recent entry again before `DirectoryManager.cleanProject` deletes `<projRoot>` recursively.
- Nothing checks whether that project's isolated IDE is running. Deleting `<projRoot>` removes the live instance's
  `config`, `system` and `logs` dirs from under it.
- Exceptions from the deletion (e.g. a file held open on Windows) escape the pooled thread unreported.
- For the main project item, `ProjectManager.get(path)` is normally null (the stale entry is cleaned,
  `StaleMainProjectCleanupActivity`), so "Remove Project" silently does nothing.

Platform API used: `Messages.showOkCancelDialog(project, message, title, okText, cancelText, icon)` as in
`ToggleMainProjectSubAction` (declaration: `com/intellij/openapi/ui/Messages.java` in
`~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/2025.1.1/9e35671cd91372a0d02e4e6b3f18be783b17c59b/ideaIU-2025.1.1-sources.jar`),
`Task.Backgroundable` as in `actions/Launch.kt`.

Decision (made with the user, 2026-10-30): refuse with an error balloon while the project's isolated IDE is running,
because deleting its dirs under a live instance corrupts it and stopping it first would make Remove a long sync-like
operation. Rejected: stop via the stop-request protocol, then delete; warn and delete anyway. Until
`plans/core-gap-09-running-detection.md` lands, a `.lock` left by a crashed IDE also blocks removal; the error text says
to close the IDE, and the user can delete the stale `.lock` by hand.

Decision (made with the user, 2026-10-30): hide "Remove Project" on the main project item, like the four Sync entries,
because the main project has no isolated data to remove (unset it first). Rejected: making it unset the main project and
drop its entries, which turns a delete action into a settings change.

Related: `plans/plugin-gap-06-open-with-ide.md` also edits `projectSubActions`; independent.

## Target behavior

| Situation | Before | After |
|---|---|---|
| choose Remove on an isolated item | deletes immediately | OK/Cancel warning dialog naming the project and `<projRoot>` |
| Cancel | n/a | nothing; popup stays closed |
| OK, IDE of the project not running | delete, popup reshown at once (may still list it) | background task "Removing <name>..." deletes, then the popup is reshown without it |
| OK, isolated instance running (`isIsolatedInstanceOpen`) | `<projRoot>` deleted under it | nothing deleted; error `notification.error.remove.project.running`; popup reshown |
| deletion throws | unreported | error `notification.error.remove.project.failed`; popup reshown (recent entry already gone) |
| recents writes | two `remove` calls | one (inside `ProjectCleaner.cleanProject`) |
| main project item | Remove listed, no-op | Remove not listed |
| reshow thread | `show()` on EDT | `show()` on the task's background thread |

## Implementation

- `services/RemoveProject.kt`:
  ```kotlin
  /** Removes the project's isolated data and recent entry. Refuses while its isolated IDE runs.
   *  @return true if the project was removed, false if it was unknown, running, or deletion failed */
  internal fun removeRecentProject(projectPath: ProjectPath, repository: IdeConfigRepository,
                                   notificationHandler: NotificationHandler): Boolean
  ```
  Body: `ProjectManager.getInstance(repository).get(projectPath) ?: return false`; if
  `ProjectLockUtils.isIsolatedInstanceOpen(repository, projectPath)` show `notification.error.remove.project.running`
  (name) and return false; then `ProjectCleaner.getInstance(repository).cleanProject(metadata)` in `try`;
  `catch (e: Throwable)`: rethrow `ControlFlowException`, log with `logger<...>().warn`, show
  `notification.error.remove.project.failed` (name, `e.message ?: e.javaClass.simpleName`), return false.
  Delete the eager `RecentProjectsIndex.remove` and its comment.
- `actions/recent/RemoveProjectSubAction.kt`:
  `return step.doFinalStep { confirmAndRemove(project, item.projectPath) }`, where the private `confirmAndRemove` (EDT,
  after the popup closed) shows `Messages.showOkCancelDialog(project, message("dialog.confirm.remove.project.message",
  name, projRoot), message("dialog.confirm.remove.project.title"), message("dialog.confirm.remove.project.ok"),
  Messages.getCancelButton(), Messages.getWarningIcon())`; on OK queues a non-cancellable `Task.Backgroundable(project,
  message("progress.removing.project", name), false)` whose `run` calls `removeRecentProject(path, repo,
  IntelliJNotificationHandler(project))` and then `RecentProjectPopupBuilder(project).show()`. `projRoot` for the
  message comes from `DirectoryManager.getInstance(repo).getProjectRoot(path)` (path computation only, no I/O).
- `actions/recent/RecentProjectPopup.kt`, `projectSubActions`: add `RemoveProjectSubAction` only when
  `!item.isMainProject` (fold it into the existing `if` that adds the Sync entries).
- `messages/ProjectJugglerBundle.properties`, next to `popup.recent.projects.action.remove`:
  ```properties
  dialog.confirm.remove.project.title=Remove Project
  dialog.confirm.remove.project.message=Remove "{0}" from Project Juggler?\n\nIts isolated settings, caches, logs and plugins in {1} will be deleted. The project files are not touched.
  dialog.confirm.remove.project.ok=Remove
  progress.removing.project=Removing {0}...
  notification.error.remove.project.running=Cannot remove "{0}" while its IDE is running. Close it and try again.
  notification.error.remove.project.failed=Failed to remove "{0}": {1}
  ```
- No core change. `ProjectCleaner.cleanProject` keeps removing the recent entry before deleting the directory, so a
  failed deletion still drops the project from the popup; the leftover dir is reused on the next launch.

## Tests

Under `plugin/src/test/kotlin/com/projectjuggler/plugin/`, Kotest `StringSpec`, `KoinTestExtension()`, `createTempDir`.

- `services/RemoveProjectTest.kt` (from plugin-tests), updated to the new signature with a
  `RecordingNotificationHandler`:
  - registered project: returns true, gone from `getRecent(10)`, `<projRoot>` deleted, no notifications.
  - unknown path: returns false, no notifications.
  - `markIsolatedInstanceOpen`: returns false, `<projRoot>` and `metadata.json` still exist, still in `getRecent(10)`,
    one error equal to `ProjectJugglerBundle.message("notification.error.remove.project.running", name)`.
  - deletion fails (Unix only: `enabledIf = { !System.getProperty("os.name").startsWith("Windows") }`): make
    `<projRoot>/system` non-writable (`toFile().setWritable(false)`) with a file inside; returns false, one error
    starting with the `notification.error.remove.project.failed` text for the name; restore permissions in `afterTest`.
- `actions/recent/RecentProjectPopupTest.kt`: the main-item case becomes `[OpenRecentProjectSubAction,
  ToggleMainProjectSubAction]` (Remove dropped); the isolated case is unchanged.

Not automatable: the dialog, the task and the reshow order. Manual check below.

## Docs

`docs/plugin-spec.md`:
- §5.2: sub-menu order sentence: "Remove Project" is omitted for the main project together with the Sync entries.
- §5.5 table, Remove Project row: "OK/Cancel warning dialog (`dialog.confirm.remove.project.*`) on EDT after the popup
  closes. On OK, a background task (`progress.removing.project`) runs `removeRecentProject`: refuses with
  `notification.error.remove.project.running` while the isolated instance is open (`isIsolatedInstanceOpen`);
  otherwise `ProjectCleaner.cleanProject(metadata)` (removes the recent entry, then deletes `<projRoot>` recursively,
  core-spec §9); a failure shows `notification.error.remove.project.failed`. The popup is reshown from the task when
  it finishes. Not offered for the main project."
- §12 threading table: the "Popup building, import, remove, worktree" row drops "remove"; add "Remove Project
  deletion and reshow | `Task.Backgroundable`"; dialogs row already covers the confirmation.
- Known gaps: delete item 7.

## Sessions

1. Single session, Fable 5.1 at default effort, ~12% context. Reads: this plan, `CLAUDE.md`,
   `actions/recent/RemoveProjectSubAction.kt`, `ToggleMainProjectSubAction.kt` (dialog pattern),
   `RecentProjectPopup.kt` (`projectSubActions`), `services/RemoveProject.kt`, core `ProjectCleaner.kt` and
   `ProjectLockUtils.isIsolatedInstanceOpen`, `RemoveProjectTest.kt`, `RecentProjectPopupTest.kt`. Implements
   everything above, runs Verification, does Finishing. Done when `:plugin:test` passes and gap 7 is gone.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.RemoveProjectTest"`
2. `./gradlew :plugin:test`
3. `./gradlew test`
4. `./gradlew :plugin:buildPlugin`
5. Manual, `./gradlew :plugin:runIde`: Remove on a closed isolated project -> dialog; Cancel keeps it; OK shows the
   progress text, then the popup reappears without it and `<base>/v2/<ideDir>/projects/<id>` is gone. Remove on a
   running isolated project -> error balloon, dir intact. The main project's sub-menu has no Remove entry.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-07-remove-project.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `confirm project removal and refuse it while the isolated IDE runs`.
