package com.projectjuggler.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ModelSerializationTest : StringSpec({
    val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }
    val installation = IdeInstallation("/test/ide", "Test IDE")

    fun keysOf(encoded: String) = Json.parseToJsonElement(encoded).jsonObject.keys

    "ProjectPath serializes as a bare string" {
        json.encodeToString(ProjectPath("/work/repo")) shouldBe "\"/work/repo\""
        json.decodeFromString<ProjectPath>("\"/work/repo\"") shouldBe ProjectPath("/work/repo")
    }

    "ProjectMetadata omits openCount when 0 and debugPort when null" {
        val metadata = ProjectMetadata(ProjectPath("/work/repo"), "2026-10-30T10:00:00Z")

        keysOf(json.encodeToString(metadata)) shouldBe setOf("path", "lastOpened")
    }

    "ProjectMetadata keeps openCount and debugPort when set" {
        val metadata = ProjectMetadata(ProjectPath("/work/repo"), "2026-10-30T10:00:00Z", openCount = 3, debugPort = 5001)

        val encoded = json.encodeToString(metadata)

        keysOf(encoded) shouldBe setOf("path", "lastOpened", "openCount", "debugPort")
        json.decodeFromString<ProjectMetadata>(encoded) shouldBe metadata
    }

    "IdeConfig.default encodes only the installation" {
        keysOf(json.encodeToString(IdeConfig.default(installation))) shouldBe setOf("installation")
    }

    "decoding a config with only the installation gives all defaults" {
        val config = json.decodeFromString<IdeConfig>(
            """{"installation": {"executablePath": "/test/ide", "displayName": "Test IDE"}}"""
        )

        config shouldBe IdeConfig.default(installation)
        config.maxRecentProjects shouldBe 10
        config.dontAskAboutMainProject shouldBe false
        config.syncPluginsOnIdeUpdate shouldBe false
        config.baseVmOptionsPath shouldBe null
        config.baseVmOptionsHash shouldBe null
        config.basePluginsPath shouldBe null
        config.baseConfigPath shouldBe null
        config.mainProjectPath shouldBe null
        config.lastKnownBuildNumber shouldBe null
    }
})
