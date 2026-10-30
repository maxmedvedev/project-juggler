package com.projectjuggler.locators

import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.collections.forEach

object IntelliJLocator {

    fun findIntelliJ(): Path? {
        return findAllInstallations()
            .firstOrNull()
            ?.let { installation -> Paths.get(installation.executablePath) }
    }

    fun findInstallation(
        executablePath: String
    ): IdeInstallation = findInstallation(executablePath, LocatorEnvironment.current())

    internal fun findInstallation(executablePath: String, env: LocatorEnvironment): IdeInstallation {
        val installations = findAllInstallations(env)
        val matching = installations.find { it.executablePath == executablePath }
        if (matching != null) {
            return matching
        }

        return IdeInstallation(executablePath, extractDisplayName(Paths.get(executablePath), env.platform))
    }

    /**
     * Finds all IntelliJ IDE installations on the system.
     * Scans standard installation directories and JetBrains Toolbox locations.
     */
    fun findAllInstallations(): List<IdeInstallation> = findAllInstallations(LocatorEnvironment.current())

    internal fun findAllInstallations(env: LocatorEnvironment): List<IdeInstallation> {
        val installations = mutableListOf<IdeInstallation>()
        val config = prepareScanConfig(env)

        scanStandardDirectories(config, installations)
        scanDirectExecutables(config, installations)
        scanToolboxDirectory(config, installations)

        if (config.platform == Platform.LINUX) {
            scanLinuxSnapInstallations(env.fsRoot.resolve("snap"), installations)
        }

        return installations.distinctBy { it.executablePath }
    }

    fun extractDisplayName(ideDir: String): String = extractDisplayName(Paths.get(ideDir))

    fun extractDisplayName(ideDir: Path): String = extractDisplayName(ideDir, Platform.current())

    private fun prepareScanConfig(env: LocatorEnvironment): ScanConfig {
        val platform = env.platform
        val userHome = env.userHome

        val config = when (platform) {
            Platform.MACOS -> ScanConfig(
                platform = platform,
                standardDirs = listOf(env.fsRoot.resolve("Applications"), userHome.resolve("Applications")),
                directExecutables = emptyList(),
                filter = { path -> path.fileName.toString().let { it.contains("IntelliJ") && it.endsWith(".app") } },
                execResolver = { path -> path.resolve("Contents/MacOS/idea") },
                toolboxDir = userHome.resolve("Library/Application Support/JetBrains/Toolbox/apps")
            )

            Platform.LINUX -> ScanConfig(
                platform = platform,
                standardDirs = listOf(env.fsRoot.resolve("opt")),
                directExecutables = listOf(
                    userHome.resolve(Paths.get("bin", "idea", "bin", "idea.sh")),
                    userHome.resolve(Paths.get("idea", "bin", "idea.sh")),
                    userHome.resolve(Paths.get(".local", "bin", "idea")),
                    userHome.resolve(Paths.get(".local", "share", "applications", "idea"))
                ),
                filter = { path ->
                    path.fileName.toString().lowercase().let { it.contains("intellij") || it == "idea" }
                },
                execResolver = { path -> path.resolve("bin/idea.sh") },
                toolboxDir = userHome.resolve(".local/share/JetBrains/Toolbox/apps")
            )

            Platform.WINDOWS -> {
                val programFiles = env.getenv("ProgramFiles") ?: "C:\\Program Files"
                val localAppData = env.getenv("LOCALAPPDATA") ?: "$userHome\\AppData\\Local"
                ScanConfig(
                    platform = platform,
                    standardDirs = listOf(
                        Paths.get(programFiles, "JetBrains"),
                        Paths.get(localAppData, "Programs")
                    ),
                    directExecutables = emptyList(),
                    filter = { path -> path.fileName.toString().contains("IntelliJ") },
                    execResolver = { path -> path.resolve("bin/idea64.exe") },
                    toolboxDir = Paths.get(localAppData, "JetBrains/Toolbox/apps")
                )
            }
        }
        return config
    }

    private fun scanStandardDirectories(config: ScanConfig, installations: MutableList<IdeInstallation>) {
        config.standardDirs.forEach { dir ->
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                try {
                    Files.list(dir).use { stream ->
                        stream.filter(config.filter).forEach { ideDir ->
                            val execPath = config.execResolver(ideDir)
                            if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                                val displayName = extractDisplayName(ideDir, config.platform)
                                installations.add(IdeInstallation(execPath.toString(), displayName))
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Skip directories we can't read
                }
            }
        }
    }

    private fun scanDirectExecutables(config: ScanConfig, installations: MutableList<IdeInstallation>) {
        config.directExecutables.forEach { execPath ->
            if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                val displayName = extractDirectExecutableDisplayName(execPath)
                installations.add(IdeInstallation(execPath.toString(), displayName))
            }
        }
    }

    private fun extractDirectExecutableDisplayName(execPath: Path): String {
        val pathStr = execPath.toString()
        return when {
            pathStr.contains(".local/bin") -> "IntelliJ IDEA (User)"
            pathStr.contains(".local/share/applications") -> "IntelliJ IDEA (User)"
            pathStr.contains("/bin/idea/") -> "IntelliJ IDEA (User)"
            pathStr.contains("/idea/bin/") -> "IntelliJ IDEA (User)"
            else -> "IntelliJ IDEA"
        }
    }

    private fun scanLinuxSnapInstallations(snapDir: Path, installations: MutableList<IdeInstallation>) {
        if (Files.exists(snapDir) && Files.isDirectory(snapDir)) {
            listOf("intellij-idea-ultimate", "intellij-idea-community").forEach { snapName ->
                val execPath = snapDir.resolve("$snapName/current/bin/idea.sh")
                if (Files.exists(execPath) && Files.isExecutable(execPath)) {
                    val displayName = if (snapName.contains("ultimate")) "IntelliJ IDEA Ultimate (Snap)" else "IntelliJ IDEA CE (Snap)"
                    installations.add(IdeInstallation(execPath.toString(), displayName))
                }
            }
        }
    }

    private fun scanToolboxDirectory(config: ScanConfig, installations: MutableList<IdeInstallation>) {
        if (!Files.exists(config.toolboxDir) || !Files.isDirectory(config.toolboxDir)) {
            return
        }
        try {
            Files.list(config.toolboxDir).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString().lowercase()
                    name.contains("idea") || name.contains("intellij")
                }.forEach { ideTypeDir ->
                    // Toolbox structure: apps/<ide-type>/ch-0/<version>/...
                    val channelDir = ideTypeDir.resolve("ch-0")
                    if (Files.exists(channelDir) && Files.isDirectory(channelDir)) {
                        try {
                            Files.list(channelDir).use { versions ->
                                versions.filter { Files.isDirectory(it) }.forEach { versionDir ->
                                    val execPath = when (config.platform) {
                                        Platform.MACOS -> {
                                            // Find .app inside version directory
                                            Files.list(versionDir).use { apps ->
                                                apps.filter { it.fileName.toString().endsWith(".app") }
                                                    .map { it.resolve("Contents/MacOS/idea") }
                                                    .filter { Files.exists(it) && Files.isExecutable(it) }
                                                    .findFirst().orElse(null)
                                            }
                                        }
                                        Platform.LINUX -> versionDir.resolve("bin/idea.sh")
                                        Platform.WINDOWS -> versionDir.resolve("bin/idea64.exe")
                                    }
                                    if (execPath != null && Files.exists(execPath) && Files.isExecutable(execPath)) {
                                        val displayName = extractToolboxDisplayName(ideTypeDir, versionDir)
                                        installations.add(IdeInstallation(execPath.toString(), displayName))
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            // Skip if we can't read the channel directory
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Skip if we can't read the toolbox directory
        }
    }

    internal fun extractDisplayName(ideDir: Path, platform: Platform): String {
        val dirName = ideDir.fileName.toString()
        return when (platform) {
            Platform.MACOS -> dirName.removeSuffix(".app")
            Platform.WINDOWS -> dirName
            Platform.LINUX -> when {
                dirName.equals("idea", ignoreCase = true) -> "IntelliJ IDEA"
                dirName.contains("ultimate", ignoreCase = true) -> "IntelliJ IDEA Ultimate"
                dirName.contains("community", ignoreCase = true) || dirName.contains("ce", ignoreCase = true) -> "IntelliJ IDEA CE"
                else -> "IntelliJ IDEA ($dirName)"
            }
        }
    }

    private fun extractToolboxDisplayName(ideTypeDir: Path, versionDir: Path): String {
        val ideType = ideTypeDir.fileName.toString().lowercase()
        val version = versionDir.fileName.toString()
        val edition = when {
            ideType.contains("ultimate") || ideType == "idea-u" -> "Ultimate"
            ideType.contains("community") || ideType.contains("ce") || ideType == "idea-c" -> "CE"
            else -> ""
        }
        val editionSuffix = if (edition.isNotEmpty()) " $edition" else ""
        return "IntelliJ IDEA$editionSuffix $version (Toolbox)"
    }
}

private data class ScanConfig(
    val platform: Platform,
    val standardDirs: List<Path>,
    val directExecutables: List<Path>,
    val filter: (Path) -> Boolean,
    val execResolver: (Path) -> Path,
    val toolboxDir: Path
)