package com.ideajuggler.core

import com.ideajuggler.config.ProjectId
import com.ideajuggler.config.ProjectPath
import com.ideajuggler.util.HashUtils

object ProjectIdGenerator {
    fun generate(projectPath: ProjectPath): ProjectId {
        // Generate SHA-256 hash and take first 16 characters
        val hash = HashUtils.calculateStringHash(projectPath.toString())
        return ProjectId(hash.take(16))
    }
}
