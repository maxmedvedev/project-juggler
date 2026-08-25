package com.projectjuggler.config

import com.projectjuggler.core.ProjectIdGenerator
import com.projectjuggler.util.PathUtils
import kotlinx.serialization.Serializable
import kotlin.io.path.Path

@JvmInline
@Serializable
value class ProjectPath(
    val pathString: String
) {
    val id: ProjectId get() = ProjectIdGenerator.generate(this)
    val path get() = Path(pathString)

    /**
     * The raw last path segment (`module.bazel` for a file-based project).
     * Used for id generation, which must stay stable - see [ProjectIdGenerator].
     */
    val fileName: String get() = path.fileName?.toString() ?: pathString

    /**
     * The human-readable project name: the enclosing directory, plus the selected file for
     * file-based projects (`~/work/my-repo/MODULE.bazel` -> `my-repo [MODULE.bazel]`), the
     * directory itself otherwise.
     *
     * The same directory can be opened by different build systems (Bazel via `MODULE.bazel`, plain
     * JPS via the directory), so the file has to be part of the name to tell those setups apart.
     */
    val name: String
        get() {
            val root = PathUtils.projectRoot(path) ?: return fileName
            val dirName = root.fileName?.toString() ?: root.toString()
            return if (root == path) dirName else "$dirName [$fileName]"
        }
}
