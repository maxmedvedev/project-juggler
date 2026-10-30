package com.projectjuggler.config

import com.projectjuggler.util.HashUtils
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class IdeInstallationTest : StringSpec({
    val executablePath = "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea"

    "id is the first 16 hex chars of the executable path hash" {
        val installation = IdeInstallation(executablePath, "IntelliJ IDEA")

        installation.id shouldBe HashUtils.calculateStringHash(executablePath).take(16)
    }

    "directoryName is the sanitized display name plus 8 chars of the id" {
        val installation = IdeInstallation(executablePath, "IntelliJ IDEA 2025.1 (Toolbox)")

        installation.directoryName shouldBe "IntelliJ-IDEA-2025.1-Toolbox-${installation.id.take(8)}"
    }

    "id depends only on the executable path" {
        IdeInstallation(executablePath, "A").id shouldBe IdeInstallation(executablePath, "B").id
    }

    "json round trip stores only executablePath and displayName" {
        val installation = IdeInstallation(executablePath, "IntelliJ IDEA")

        val encoded = Json.encodeToString(installation)

        Json.parseToJsonElement(encoded).jsonObject.keys shouldBe setOf("executablePath", "displayName")
        val decoded = Json.decodeFromString<IdeInstallation>(encoded)
        decoded shouldBe installation
        decoded.id shouldBe installation.id
        decoded.directoryName shouldBe installation.directoryName
    }
})
