package com.projectjuggler.plugin.test

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectManager
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Repository for a fake IDE installation rooted at [baseDir].
 */
fun testRepository(baseDir: Path): IdeConfigRepository =
    IdeConfigRepository(baseDir, IdeInstallation("/test/ide/bin/idea", "Test IDE"))

/**
 * Registers the project at [dir] the way a launch does: metadata plus a recent entry.
 */
fun registerProject(repository: IdeConfigRepository, dir: Path): ProjectMetadata {
    val projectPath = ProjectPath(dir.toString())
    val metadata = ProjectManager.getInstance(repository).registerOrUpdate(projectPath)
    RecentProjectsIndex.getInstance(repository).recordOpen(projectPath)
    return metadata
}

/**
 * Simulates a running isolated instance of [projectPath] by writing the IDE's `.lock` file into the
 * project's isolated config directory. The lock holds the current (live) PID, so the project also
 * counts as open once liveness is checked against the process.
 */
fun markIsolatedInstanceOpen(repository: IdeConfigRepository, projectPath: ProjectPath) {
    val lockFile = repository.baseDir
        .resolve("projects")
        .resolve(projectPath.id.id)
        .resolve("config")
        .resolve(".lock")
    lockFile.parent.createDirectories()
    lockFile.writeText(ProcessHandle.current().pid().toString())
}

/**
 * Makes [dir] look like a git checkout of [branch] so `GitUtils.detectGitBranch` works without a git binary.
 */
fun writeGitHead(dir: Path, branch: String) {
    val gitDir = dir.resolve(".git").createDirectories()
    gitDir.resolve("HEAD").writeText("ref: refs/heads/$branch\n")
}
