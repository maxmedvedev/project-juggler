package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.util.HashUtils
import java.nio.file.Path
import kotlin.io.path.exists

class BaseVMOptionsTracker(private val configRepository: ConfigRepository) {

    fun hasChanged(): Boolean {
        val config = configRepository.load()
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
        val config = configRepository.load()
        val baseVmOptionsPath = config.baseVmOptionsPath ?: return
        val basePath = Path.of(baseVmOptionsPath)

        if (!basePath.exists()) {
            return
        }

        val currentHash = HashUtils.calculateFileHash(basePath)
        configRepository.update { it.copy(baseVmOptionsHash = currentHash) }
    }

    fun getBaseVmOptionsPath(): Path? {
        val config = configRepository.load()
        val pathString = config.baseVmOptionsPath ?: return null
        val path = Path.of(pathString)
        return if (path.exists()) path else null
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository) = BaseVMOptionsTracker(configRepository)
    }
}
