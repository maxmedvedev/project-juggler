package com.projectjuggler.plugin.test

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.common.initializeTestEnvironment

/**
 * Runs [block], which must log exactly one error through IntelliJ's `Logger`, and returns that error.
 *
 * In tests the IDE's logger turns `LOG.error(...)` into a thrown assertion (the default logger and the
 * test logger both do), so a spec exercising an error-handling path has to intercept the logged error.
 * The test logger is installed first so the behavior is the same whether or not a spec has already
 * booted the test application in this JVM.
 */
fun expectLoggedError(block: () -> Unit): Throwable {
    initializeTestEnvironment()
    return LoggedErrorProcessor.executeAndReturnLoggedError(block)
}
