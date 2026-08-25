package com.projectjuggler.core

import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.util.HashUtils

object ProjectIdGenerator {
    fun generate(projectPath: ProjectPath): ProjectId {
        val hash = HashUtils.calculateStringHash(projectPath.toString())
        // Deliberately the raw file name, not the display name: the id is the directory name for a
        // project's isolated config/system/logs/plugins, so it must not change for existing projects.
        return ProjectId(projectPath.fileName + "-" + hash.take(16))
    }
}
