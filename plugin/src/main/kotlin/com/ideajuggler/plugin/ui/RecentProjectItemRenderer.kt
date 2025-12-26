package com.ideajuggler.plugin.ui

import com.ideajuggler.plugin.model.RecentProjectItem
import com.intellij.ide.RecentProjectIconHelper
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.*

internal class RecentProjectItemRenderer : ListCellRenderer<RecentProjectItem> {
    private val iconHelper = RecentProjectIconHelper()

    override fun getListCellRendererComponent(
        list: JList<out RecentProjectItem>,
        value: RecentProjectItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) {
            return JPanel()
        }

        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(4, 6)

        // Get project icon using IntelliJ's icon helper
        val projectIcon = iconHelper.getProjectIcon(
            path = value.metadata.path.path,
            isProjectValid = true,
            name = value.metadata.name
        )
        val iconLabel = JLabel(projectIcon)
        iconLabel.border = JBUI.Borders.emptyRight(8)
        panel.add(iconLabel, BorderLayout.WEST)

        // Content panel with multiple lines
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // First line: Project name
        val nameComponent = SimpleColoredComponent()
        if (isSelected) {
            nameComponent.append(value.metadata.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UIUtil.getListSelectionForeground(cellHasFocus)))
        } else {
            nameComponent.append(value.metadata.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
        nameComponent.isOpaque = false
        contentPanel.add(nameComponent)

        // Second line: Git branch (if present)
        if (value.gitBranch != null) {
            val branchComponent = SimpleColoredComponent()
            if (isSelected) {
                branchComponent.append("[${value.gitBranch}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getListSelectionForeground(cellHasFocus)))
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
            pathComponent.append(compactPath, SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, UIUtil.getListSelectionForeground(cellHasFocus)))
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
