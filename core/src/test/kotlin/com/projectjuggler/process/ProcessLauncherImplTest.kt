package com.projectjuggler.process

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey

class ProcessLauncherImplTest : StringSpec({
    "applyEnvironment sets non-null values and removes null ones" {
        val builder = ProcessBuilder()
        builder.environment()["PJ_TEST_REMOVED"] = "inherited"

        builder.applyEnvironment(mapOf("PJ_TEST_SET" to "1", "PJ_TEST_REMOVED" to null))

        builder.environment() shouldContain ("PJ_TEST_SET" to "1")
        builder.environment() shouldNotContainKey "PJ_TEST_REMOVED"
    }
})
