package com.projectjuggler.plugin.actions.recent

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.projectjuggler.plugin.ProjectJugglerBundle
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

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
            is RecentProjectItem -> renderRecentProject(value, isSelected, cellHasFocus)
            is OpenFileChooserItem -> renderOpenFileChooser(isSelected, cellHasFocus)
            is SyncAllProjectsItem -> renderSyncAllProjects(isSelected, cellHasFocus)
        }
    }

    private fun renderRecentProject(
        value: RecentProjectItem,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        panel.accessibleContext.accessibleName = value.metadata.name

        panel.border = JBUI.Borders.empty(4, 6)

        // Get project icon using IntelliJ's icon helper
        val projectIcon = recentProjectsManager.getProjectIcon(value.metadata.path.path, true)
        val iconLabel = JLabel(projectIcon)
        iconLabel.verticalAlignment = SwingConstants.TOP
        iconLabel.border = JBUI.Borders.empty(2, 0, 0, 8)
        panel.add(iconLabel, BorderLayout.WEST)

        // Content panel with multiple lines
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // First line: Project name
        val nameComponent = SimpleColoredComponent()
        if (isSelected) {
            nameComponent.append(value.metadata.name,
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getListSelectionForeground(cellHasFocus))
            )
        } else {
            nameComponent.append(value.metadata.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        nameComponent.isOpaque = false
        contentPanel.add(nameComponent)

        // Second line: Git branch (if present)
        if (value.gitBranch != null) {
            val branchComponent = SimpleColoredComponent()
            if (isSelected) {
                branchComponent.append("[${value.gitBranch}]",
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        UIUtil.getListSelectionForeground(cellHasFocus)
                    )
                )
            } else {
                branchComponent.append("[${value.gitBranch}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            branchComponent.isOpaque = false
            contentPanel.add(branchComponent)
        }

        // Third line: Path
        val path = value.metadata.path.pathString
        val compactPath = compactPath(path)
        val pathComponent = SimpleColoredComponent()
        if (isSelected) {
            pathComponent.append(compactPath,
                SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_SMALLER,
                    UIUtil.getListSelectionForeground(cellHasFocus)
                )
            )
        } else {
            pathComponent.append(compactPath, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        pathComponent.isOpaque = false
        contentPanel.add(pathComponent)

        panel.add(contentPanel, BorderLayout.CENTER)

        // Handle selection colors
        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(cellHasFocus)
            panel.foreground = UIUtil.getListSelectionForeground(cellHasFocus)
        } else {
            panel.background = UIUtil.getListBackground()
            panel.foreground = UIUtil.getListForeground()
        }

        return panel
    }

    private fun renderOpenFileChooser(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        panel.accessibleContext.accessibleName = ProjectJugglerBundle.message("popup.open.file.chooser.label")

        // Add top separator border
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBUI.CurrentTheme.Popup.separatorColor(), 1, 0, 0, 0),
            JBUI.Borders.empty(6, 6, 4, 6),
            true
        )

        // Folder icon
        val iconLabel = JLabel(AllIcons.Nodes.Folder)
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.border = JBUI.Borders.empty(0, 0, 0, 8)
        panel.add(iconLabel, BorderLayout.WEST)

        // Label text
        val textComponent = SimpleColoredComponent()
        val label = ProjectJugglerBundle.message("popup.open.file.chooser.label")
        if (isSelected) {
            textComponent.append(label,
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getListSelectionForeground(cellHasFocus))
            )
        } else {
            textComponent.append(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        textComponent.isOpaque = false
        panel.add(textComponent, BorderLayout.CENTER)

        // Handle selection colors
        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(cellHasFocus)
            panel.foreground = UIUtil.getListSelectionForeground(cellHasFocus)
        } else {
            panel.background = UIUtil.getListBackground()
            panel.foreground = UIUtil.getListForeground()
        }

        return panel
    }

    private fun renderSyncAllProjects(
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout())
        panel.accessibleContext.accessibleName = "Sync all projects"

        // Add top separator border
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBUI.CurrentTheme.Popup.separatorColor(), 1, 0, 0, 0),
            JBUI.Borders.empty(6, 6, 4, 6),
            true
        )

        // Refresh/sync icon
        val iconLabel = JLabel(AllIcons.Actions.Refresh)
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.border = JBUI.Borders.empty(0, 0, 0, 8)
        panel.add(iconLabel, BorderLayout.WEST)

        // Label text
        val textComponent = SimpleColoredComponent()
        val label = "Sync all projects"
        if (isSelected) {
            textComponent.append(label,
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getListSelectionForeground(cellHasFocus))
            )
        } else {
            textComponent.append(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        textComponent.isOpaque = false
        panel.add(textComponent, BorderLayout.CENTER)

        // Handle selection colors
        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(cellHasFocus)
            panel.foreground = UIUtil.getListSelectionForeground(cellHasFocus)
        } else {
            panel.background = UIUtil.getListBackground()
            panel.foreground = UIUtil.getListForeground()
        }

        return panel
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