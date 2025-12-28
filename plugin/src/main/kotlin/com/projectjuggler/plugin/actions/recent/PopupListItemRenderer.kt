package com.projectjuggler.plugin.actions.recent

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ui.IconManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.Borders.empty
import com.intellij.util.ui.UIUtil
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import java.awt.BorderLayout
import java.awt.BorderLayout.CENTER
import java.awt.BorderLayout.WEST
import java.awt.Component
import java.io.File
import javax.swing.*
import javax.swing.BoxLayout
import javax.swing.BoxLayout.Y_AXIS
import javax.swing.SwingConstants
import javax.swing.SwingConstants.TOP

internal class PopupListItemRenderer : ListCellRenderer<PopupListItem> {
    private val recentProjectsManager = RecentProjectsManager.getInstance() as RecentProjectsManagerBase

    override fun getListCellRendererComponent(
        list: JList<out PopupListItem>,
        value: PopupListItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) {
            return JPanel()
        }

        return when (value) {
            is RecentProjectItem -> renderRecentProject(value, isSelected, cellHasFocus, value.projectPath)
            is OpenFileChooserItem -> renderOpenFileChooser(isSelected, cellHasFocus)
            is SyncProjectsItem -> renderSyncProjects(value.syncType, isSelected, cellHasFocus)
        }
    }

    private fun renderRecentProject(
        value: RecentProjectItem,
        isSelected: Boolean,
        cellHasFocus: Boolean,
        path: ProjectPath
    ): Component {
        val panel = JPanel(BorderLayout())
        panel.accessibleContext.accessibleName = path.name
        panel.border = empty(4, 6)

        // Get project icon using IntelliJ's icon helper
        var projectIcon = recentProjectsManager.getProjectIcon(path.path, true)

        // Add green dot badge if project is currently open
        if (value.isOpen) {
            val greenColor = JBColor(0x5CB85C, 0x5CB85C) // Green color for "running" status
            projectIcon = IconManager.getInstance().withIconBadge(projectIcon, greenColor)
        }

        val iconLabel = JLabel(projectIcon)
        iconLabel.verticalAlignment = TOP
        iconLabel.border = empty(2, 0, 0, 8)
        panel.add(iconLabel, WEST)

        // Content panel with multiple lines
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, Y_AXIS)
        contentPanel.isOpaque = false

        // First line: Project name
        val nameComponent = SimpleColoredComponent().apply {
            append(path.name, getRegularTextAttributes(isSelected, cellHasFocus))
            isOpaque = false
        }
        contentPanel.add(nameComponent)
        // Second line: Git branch (if present)
        if (value.gitBranch != null) {
            val branchComponent = SimpleColoredComponent().apply {
                append(
                    "[${value.gitBranch}]",
                    getAdditionalDataAttributes(isSelected = isSelected, cellHasFocus = cellHasFocus)
                )
                this.isOpaque = false
            }
            contentPanel.add(branchComponent)
        }
        // Third line: Path
        val pathComponent = SimpleColoredComponent().apply {
            val compactPath = compactPath(path.pathString)
            append(compactPath, getAdditionalDataAttributes(isSelected = isSelected, cellHasFocus = cellHasFocus))
            this.isOpaque = false
        }
        contentPanel.add(pathComponent)
        panel.add(contentPanel, CENTER)
        applySelectionColors(panel, isSelected = isSelected, cellHasFocus = cellHasFocus)
        return panel
    }

    private fun getAdditionalDataAttributes(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): SimpleTextAttributes = when {
        isSelected -> SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER,
            UIUtil.getListSelectionForeground(cellHasFocus)
        )
        else -> SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
    }

    private fun renderOpenFileChooser(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return renderActionItem(
            label = ProjectJugglerBundle.message("popup.open.file.chooser.label"),
            icon = AllIcons.Nodes.Folder,
            isSelected = isSelected,
            cellHasFocus = cellHasFocus
        )
    }

    private fun renderSyncProjects(
        syncType: SyncType,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val label = when (syncType) {
            SyncType.All -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
            SyncType.VmOptions -> ProjectJugglerBundle.message("popup.sync.vmoptions.label")
            SyncType.Config -> ProjectJugglerBundle.message("popup.sync.config.label")
            SyncType.Plugins -> ProjectJugglerBundle.message("popup.sync.plugins.label")
        }
        return renderActionItem(
            label = label,
            icon = AllIcons.Actions.Refresh,
            isSelected = isSelected,
            cellHasFocus = cellHasFocus
        )
    }

    private fun renderActionItem(
        label: String,
        icon: Icon,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val outerPanel = JPanel(BorderLayout())
        outerPanel.isOpaque = false
        outerPanel.border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.Popup.separatorColor())

        val contentPanel = JPanel(BorderLayout())
        contentPanel.accessibleContext.accessibleName = label
        contentPanel.border = empty(6, 6, 4, 6)

        // Icon
        val iconLabel = JLabel(icon)
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.border = JBUI.Borders.emptyRight(8)
        contentPanel.add(iconLabel, WEST)

        // Label text
        val attributes = getRegularTextAttributes(isSelected, cellHasFocus)
        val textComponent = SimpleColoredComponent()
        textComponent.append(label, attributes)
        textComponent.isOpaque = false
        contentPanel.add(textComponent, CENTER)

        applySelectionColors(contentPanel, isSelected, cellHasFocus)

        outerPanel.add(contentPanel, CENTER)

        return outerPanel
    }

    private fun getRegularTextAttributes(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): SimpleTextAttributes {
        val attributes = if (isSelected) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getListSelectionForeground(cellHasFocus))
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        return attributes
    }

    private fun applySelectionColors(panel: JPanel, isSelected: Boolean, cellHasFocus: Boolean) {
        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(cellHasFocus)
            panel.foreground = UIUtil.getListSelectionForeground(cellHasFocus)
        } else {
            panel.background = UIUtil.getListBackground()
            panel.foreground = UIUtil.getListForeground()
        }
    }

    private fun compactPath(path: String): String {
        // Replace home directory with ~
        val home = System.getProperty("user.home")
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length)
        }

        // If path is too long, show parent directory name and project name
        val file = File(path)
        val parent = file.parentFile
        if (parent != null && path.length > 60) {
            return ".../${parent.name}/${file.name}"
        }

        return path
    }
}