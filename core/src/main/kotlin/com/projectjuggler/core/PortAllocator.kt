package com.projectjuggler.core

class PortAllocator(
    private val projectManager: ProjectManager
) {
    companion object {
        const val MIN_PORT = 5001
        const val MAX_PORT = 15000
    }

    /**
     * Allocates the first debug port in [MIN_PORT, MAX_PORT) not used by another project of this IDE.
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
