package com.ideajuggler.core

class PortAllocator(
    private val projectManager: ProjectManager
) {
    companion object {
        const val MIN_PORT = 5001
        const val MAX_PORT = 15000
    }

    /**
     * Allocates the first available debug port in the range [5000, 15000).
     * Returns null if all ports are exhausted.
     */
    fun allocatePort(): Int? {
        val usedPorts = projectManager.listAll()
            .mapNotNull { it.debugPort }
            .toSet()

        for (port in MIN_PORT until MAX_PORT) {
            if (port !in usedPorts) {
                return port
            }
        }

        return null  // All ports exhausted
    }
}
