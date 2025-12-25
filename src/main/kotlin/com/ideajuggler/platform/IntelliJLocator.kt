package com.ideajuggler.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class IntelliJLocator {

    fun findIntelliJ(): Path? {
        val candidates = when (Platform.current()) {
            Platform.MACOS -> getMacOSCandidates()
            Platform.LINUX -> getLinuxCandidates()
            Platform.WINDOWS -> getWindowsCandidates()
        }

        return candidates.firstOrNull { Files.exists(it) && Files.isExecutable(it) }
    }

    private fun getMacOSCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        return listOf(
            // Standard installations
            "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
            "/Applications/IntelliJ IDEA Ultimate.app/Contents/MacOS/idea",
            "/Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea",

            // User-specific installations
            "$userHome/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
            "$userHome/Applications/IntelliJ IDEA Ultimate.app/Contents/MacOS/idea",
            "$userHome/Applications/IntelliJ IDEA CE.app/Contents/MacOS/idea",

            // Toolbox installations (common pattern)
            "$userHome/Library/Application Support/JetBrains/Toolbox/apps/intellij-idea-ultimate/bin/idea",
            "$userHome/Library/Application Support/JetBrains/Toolbox/apps/intellij-idea-ce/bin/idea",
            "$userHome/Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/*/IntelliJ IDEA.app/Contents/MacOS/idea",
            "$userHome/Library/Application Support/JetBrains/Toolbox/apps/IDEA-C/ch-0/*/IntelliJ IDEA CE.app/Contents/MacOS/idea"
        ).map { Paths.get(expandPath(it)) }
    }

    private fun getLinuxCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        return listOf(
            // Standard installations
            "/opt/idea/bin/idea.sh",
            "/opt/intellij-idea/bin/idea.sh",
            "/opt/intellij-idea-ultimate/bin/idea.sh",
            "/opt/intellij-idea-ce/bin/idea.sh",

            // User-specific installations
            "$userHome/bin/idea/bin/idea.sh",
            "$userHome/idea/bin/idea.sh",
            "$userHome/.local/bin/idea",
            "$userHome/.local/share/applications/idea",

            // Toolbox installations
            "$userHome/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate/bin/idea.sh",
            "$userHome/.local/share/JetBrains/Toolbox/apps/intellij-idea-ce/bin/idea.sh",
            "$userHome/.local/share/JetBrains/Toolbox/apps/IDEA-U/ch-0/*/bin/idea.sh",
            "$userHome/.local/share/JetBrains/Toolbox/apps/IDEA-C/ch-0/*/bin/idea.sh",

            // Snap installations
            "/snap/intellij-idea-ultimate/current/bin/idea.sh",
            "/snap/intellij-idea-community/current/bin/idea.sh"
        ).map { Paths.get(expandPath(it)) }
    }

    private fun getWindowsCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome\\AppData\\Local"

        return listOf(
            // Standard installations
            "$programFiles\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
            "$programFiles\\JetBrains\\IntelliJ IDEA Ultimate\\bin\\idea64.exe",
            "$programFiles\\JetBrains\\IntelliJ IDEA Community Edition\\bin\\idea64.exe",

            // User-specific installations
            "$localAppData\\Programs\\IntelliJ IDEA\\bin\\idea64.exe",
            "$localAppData\\Programs\\IntelliJ IDEA Ultimate\\bin\\idea64.exe",
            "$localAppData\\Programs\\IntelliJ IDEA Community Edition\\bin\\idea64.exe",

            // Toolbox installations
            "$localAppData\\JetBrains\\Toolbox\\apps\\intellij-idea-ultimate\\bin\\idea64.exe",
            "$localAppData\\JetBrains\\Toolbox\\apps\\intellij-idea-ce\\bin\\idea64.exe",
            "$localAppData\\JetBrains\\Toolbox\\apps\\IDEA-U\\ch-0\\*\\bin\\idea64.exe",
            "$localAppData\\JetBrains\\Toolbox\\apps\\IDEA-C\\ch-0\\*\\bin\\idea64.exe"
        ).map { Paths.get(expandPath(it)) }
    }

    private fun expandPath(path: String): String {
        return path
            .replace("~", System.getProperty("user.home"))
            .replace("\${user.home}", System.getProperty("user.home"))
            .replace("\${LOCALAPPDATA}", System.getenv("LOCALAPPDATA") ?: "")
            .replace("\${ProgramFiles}", System.getenv("ProgramFiles") ?: "C:\\Program Files")
    }
}
