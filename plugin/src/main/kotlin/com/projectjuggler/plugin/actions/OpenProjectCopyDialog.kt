package com.projectjuggler.plugin.actions

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.ProjectCopyField
import com.projectjuggler.plugin.services.ProjectCopyInput
import com.projectjuggler.plugin.services.ProjectCopyValidation
import com.projectjuggler.plugin.services.suggestDestination
import com.projectjuggler.plugin.services.validateProjectCopyInput
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Asks for the source repository, the new branch and the worktree folder of a project copy.
 *
 * The destination follows `<source>-<branch>` until the user edits it; its browse button picks the parent
 * folder, because a folder chooser cannot select a folder that does not exist yet. Validation runs on OK.
 */
internal class OpenProjectCopyDialog(project: Project?) : DialogWrapper(project, true) {
    private val sourceField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir()
                .withTitle(ProjectJugglerBundle.message("file.chooser.source.title"))
                .withDescription(ProjectJugglerBundle.message("file.chooser.source.description"))
        )
    }
    private val branchField = JBTextField()
    private val destinationField = TextFieldWithBrowseButton()

    private var destinationEdited = false
    private var updatingDestination = false

    /** Current text of the three fields; read after [showAndGet] returned true. */
    val input: ProjectCopyInput
        get() = ProjectCopyInput(sourceField.text, branchField.text, destinationField.text)

    init {
        title = ProjectJugglerBundle.message("dialog.project.copy.title")
        setOKButtonText(ProjectJugglerBundle.message("dialog.project.copy.ok"))

        destinationField.addActionListener { browseDestinationParent(project) }

        val suggestOnChange = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateSuggestedDestination()
        }
        sourceField.textField.document.addDocumentListener(suggestOnChange)
        branchField.document.addDocumentListener(suggestOnChange)
        destinationField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updatingDestination) destinationEdited = true
            }
        })

        init()
    }

    private fun suggestedDestination(): String? = suggestDestination(sourceField.text, branchField.text)

    private fun updateSuggestedDestination() {
        if (destinationEdited) return
        updatingDestination = true
        try {
            destinationField.text = suggestedDestination().orEmpty()
        } finally {
            updatingDestination = false
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row(ProjectJugglerBundle.message("dialog.project.copy.source.label")) {
            cell(sourceField).align(AlignX.FILL)
        }
        row(ProjectJugglerBundle.message("dialog.project.copy.branch.label")) {
            cell(branchField).align(AlignX.FILL)
        }
        row(ProjectJugglerBundle.message("dialog.project.copy.destination.label")) {
            cell(destinationField).align(AlignX.FILL)
        }.rowComment(ProjectJugglerBundle.message("dialog.project.copy.destination.comment"))
    }

    override fun getPreferredFocusedComponent(): JComponent = sourceField

    override fun doValidate(): ValidationInfo? {
        val invalid = validateProjectCopyInput(input) as? ProjectCopyValidation.Invalid ?: return null
        val component = when (invalid.field) {
            ProjectCopyField.SOURCE -> sourceField.textField
            ProjectCopyField.BRANCH -> branchField
            ProjectCopyField.DESTINATION -> destinationField.textField
        }
        return ValidationInfo(invalid.message, component)
    }

    /** Picks the parent folder and keeps the current (or suggested) worktree folder name. */
    private fun browseDestinationParent(project: Project?) {
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(ProjectJugglerBundle.message("file.chooser.destination.title"))
            .withDescription(ProjectJugglerBundle.message("file.chooser.destination.description"))
        FileChooser.chooseFile(descriptor, project, destinationField, null) { chosen ->
            val parent = Path.of(chosen.path)
            val name = folderName(destinationField.text) ?: folderName(suggestedDestination())
            destinationField.text = name?.let { parent.resolve(it).toString() } ?: parent.toString()
        }
    }

    private fun folderName(path: String?): String? = try {
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).fileName?.toString() }
    } catch (_: InvalidPathException) {
        null
    }
}
