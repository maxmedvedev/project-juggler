package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.di.getScope
import com.projectjuggler.util.HashUtils
import java.nio.file.Path
import kotlin.io.path.exists

class BaseVMOptionsTracker internal constructor(
    private val ideConfigRepository: IdeConfigRepository
) {
    fun hasChanged(): Boolean {
        val config = ideConfigRepository.load()
        val baseVmOptionsPath = config.baseVmOptionsPath ?: return false
        val basePath = Path.of(baseVmOptionsPath)

        if (!basePath.exists()) {
            return false
        }

        val currentHash = HashUtils.calculateFileHash(basePath)
        val storedHash = config.baseVmOptionsHash

        return currentHash != storedHash
    }

    fun updateHash() {
        val config = ideConfigRepository.load()
        val baseVmOptionsPath = config.baseVmOptionsPath ?: return
        val basePath = Path.of(baseVmOptionsPath)

        if (!basePath.exists()) {
            return
        }

        val currentHash = HashUtils.calculateFileHash(basePath)
        ideConfigRepository.update {
            it.copy(baseVmOptionsHash = currentHash)
        }
    }

    fun getBaseVmOptionsPath(): Path? {
        val config = ideConfigRepository.load()
        val pathString = config.baseVmOptionsPath ?: return null
        val path = Path.of(pathString)
        return if (path.exists()) path else null
    }

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository): BaseVMOptionsTracker =
            ideConfigRepository.getScope().get()
    }
}
