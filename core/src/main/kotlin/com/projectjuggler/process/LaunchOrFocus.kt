package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.process.WindowFocuser.FocusResult
import com.projectjuggler.util.ProjectLockUtils

/** What opening a project from outside its IDE does; see [planOpen]. */
enum class OpenStep { FOCUS, LAUNCH }

/**
 * The step that opens [projectPath] of [repository]'s IDE from outside that IDE:
 * - [OpenStep.LAUNCH] for the main project: a running main IDE opens or focuses it, which a lock-based focus could
 *   not do.
 * - [OpenStep.FOCUS] when the project's isolated IDE is running, [OpenStep.LAUNCH] when it is not.
 *
 * Planning creates no directories.
 */
fun planOpen(repository: IdeConfigRepository, projectPath: ProjectPath): OpenStep = when {
    MainProjectService.isMainProject(repository, projectPath) -> OpenStep.LAUNCH
    ProjectLockUtils.isProjectOpen(repository, projectPath) -> OpenStep.FOCUS
    else -> OpenStep.LAUNCH
}

/**
 * Focuses the window of the IDE running [projectPath], found through the PID in the project's lock file.
 * Returns [FocusResult.LockNotFound] without a PID and [FocusResult.ProcessNotFound] when that process is dead;
 * [focus] is called only for a live PID.
 */
fun focusRunningProject(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    focus: (Int) -> FocusResult = WindowFocuser::focus,
): FocusResult {
    val pid = ProjectLockUtils.readPidFromLock(repository, projectPath) ?: return FocusResult.LockNotFound
    if (!ProjectLockUtils.isProcessRunning(pid)) return FocusResult.ProcessNotFound(pid)
    return focus(pid)
}
