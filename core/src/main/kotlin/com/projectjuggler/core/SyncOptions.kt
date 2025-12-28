package com.projectjuggler.core

/**
 * Configuration options for sync operations.
 */
data class SyncOptions(
    /**
     * Whether to stop the project if it's currently running before syncing.
     * Default: false (caller should set to true when needed)
     */
    val stopIfRunning: Boolean = false,

    /**
     * Whether to automatically restart the project after syncing.
     * Only applies if the project was running and was stopped.
     * Default: false (caller should set to true when needed)
     */
    val autoRestart: Boolean = false,

    /**
     * Timeout in seconds for waiting for the project to shut down.
     * Default: 60 seconds
     */
    val shutdownTimeout: Int = 60,

    /**
     * Callback for progress updates during sync operation.
     */
    val onProgress: (SyncProgress) -> Unit = {}
) {
    companion object {
        /**
         * Default sync options with stop/restart disabled.
         */
        val DEFAULT = SyncOptions()

        /**
         * Sync options with stop and restart enabled (CLI default behavior).
         */
        val STOP_AND_RESTART = SyncOptions(
            stopIfRunning = true,
            autoRestart = true
        )
    }
}

/**
 * Progress updates during sync operation.
 */
sealed class SyncProgress {
    /**
     * Waiting for IntelliJ to shut down.
     * @param secondsElapsed Number of seconds elapsed since shutdown request
     */
    data class Stopping(val secondsElapsed: Int) : SyncProgress()

    /**
     * Performing sync operations (copying files).
     */
    object Syncing : SyncProgress()

    /**
     * Restarting IntelliJ after sync.
     */
    object Restarting : SyncProgress()

    /**
     * Error occurred during sync.
     * @param message Error message
     */
    data class Error(val message: String) : SyncProgress()
}
