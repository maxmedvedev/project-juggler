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
     * The human-readable project name: the enclosing directory for file-based projects
     * (`~/work/my-repo/MODULE.bazel` -> `my-repo`), the directory itself otherwise.
     */
    val name: String get() = PathUtils.projectRoot(path)?.fileName?.toString() ?: fileName
}
