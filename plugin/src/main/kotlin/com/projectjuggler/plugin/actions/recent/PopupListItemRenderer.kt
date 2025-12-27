package com.projectjuggler.plugin.actions.recent

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.*

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
            is MainProjectItem -> renderMainProject(value, isSelected, cellHasFocus)
            is RecentProjectItem -> renderRecentProject(value, isSelected, cellHasFocus)
            is OpenFileChooserItem -> renderOpenFileChooser(isSelected, cellHasFocus)
            is SyncAllProjectsItem -> renderSyncAllProjects(isSelected, cellHasFocus)
        }
    }

    private fun renderMainProject(
        value: MainProjectItem,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return renderProjectItem(
            path = ProjectPath(value.path.pathString),
            gitBranch = value.gitBranch,
            isSelected = isSelected,
            cellHasFocus = cellHasFocus,
            addBottomSeparator = true
        )
    }

    private fun renderRecentProject(
        value: RecentProjectItem,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return renderProjectItem(
            path = value.metadata.path,
            gitBranch = value.gitBranch,
            isSelected = isSelected,
            cellHasFocus = cellHasFocus,
            addBottomSeparator = false
        )
    }

    private fun renderProjectItem(
        path: ProjectPath,
        gitBranch: String?,
        isSelected: Boolean,
        cellHasFocus: Boolean,
        addBottomSeparator: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        panel.accessibleContext.accessibleName = path.name
        panel.border = JBUI.Borders.empty(4, 6)

        // Get project icon using IntelliJ's icon helper
        val projectIcon = recentProjectsManager.getProjectIcon(path.path, true)
        val iconLabel = JLabel(projectIcon)
        iconLabel.verticalAlignment = SwingConstants.TOP
        iconLabel.border = JBUI.Borders.empty(2, 0, 0, 8)
        panel.add(iconLabel, BorderLayout.WEST)

        // Content panel with multiple lines
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // First line: Project name
        val nameComponent = SimpleColoredComponent().apply {
            append(path.name, getRegularTextAttributes(isSelected, cellHasFocus))
            isOpaque = false
        }
        contentPanel.add(nameComponent)

        // Second line: Git branch (if present)
        if (gitBranch != null) {
            val branchComponent = SimpleColoredComponent().apply {
                append("[$gitBranch]", getAdditionalDataAttributes(isSelected, cellHasFocus))
                isOpaque = false
            }
            contentPanel.add(branchComponent)
        }

        // Third line: Path
        val pathComponent = SimpleColoredComponent().apply {
            val compactPath = compactPath(path.pathString)
            append(compactPath, getAdditionalDataAttributes(isSelected, cellHasFocus))
            isOpaque = false
        }
        contentPanel.add(pathComponent)

        panel.add(contentPanel, BorderLayout.CENTER)

        applySelectionColors(panel, isSelected, cellHasFocus)

        return if (addBottomSeparator) {
            val outerPanel = JPanel(BorderLayout())
            outerPanel.isOpaque = false
            outerPanel.border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Popup.separatorColor())
            outerPanel.add(panel, BorderLayout.CENTER)
            outerPanel
        } else {
            panel
        }
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

    private fun renderSyncAllProjects(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return renderActionItem(
            label = "Sync all projects",
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
        contentPanel.border = JBUI.Borders.empty(6, 6, 4, 6)

        // Icon
        val iconLabel = JLabel(icon)
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.border = JBUI.Borders.emptyRight(8)
        contentPanel.add(iconLabel, BorderLayout.WEST)

        // Label text
        val attributes = getRegularTextAttributes(isSelected, cellHasFocus)
        val textComponent = SimpleColoredComponent()
        textComponent.append(label, attributes)
        textComponent.isOpaque = false
        contentPanel.add(textComponent, BorderLayout.CENTER)

        applySelectionColors(contentPanel, isSelected, cellHasFocus)

        outerPanel.add(contentPanel, BorderLayout.CENTER)

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