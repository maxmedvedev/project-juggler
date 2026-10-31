package com.projectjuggler.plugin.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.application
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ProjectJugglerBundle.message
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathKind
import com.projectjuggler.plugin.services.JugglerSettings
import com.projectjuggler.plugin.services.MainProjectChange
import com.projectjuggler.plugin.services.applyMainProjectChange
import com.projectjuggler.plugin.services.loadJugglerSettings
import com.projectjuggler.plugin.services.saveJugglerSettings
import com.projectjuggler.plugin.services.validateBasePath
import com.projectjuggler.plugin.services.validateMainProject
import com.projectjuggler.plugin.services.withCurrentIdePaths
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

private val LOG = logger<ProjectJugglerConfigurable>()

/**
 * Settings > Tools > Project Juggler: the current IDE's `IdeConfig` (base paths, main project, recent limit,
 * checkboxes). Editable only in the main instance. The logic lives in `services/JugglerSettings.kt`; the main
 * project is changed through `setMainProject` / `clearMainProject` on a pooled thread, as from the popup.
 *
 * `Configurable` methods run on the EDT, so loading and saving `config.json` (and the path checks of the
 * validation) happen there; everything else is off the EDT.
 */
internal class ProjectJugglerConfigurable internal constructor(
    repositoryProvider: () -> IdeConfigRepository,
    currentPathsProvider: () -> CurrentIdePaths,
) : BoundSearchableConfigurable(ProjectJugglerBundle.message("settings.display.name"), "", ID) {

    constructor() : this({ IdeInstallationService.currentIdeConfigRepository }, CurrentIdePaths::detect)

    private val repository by lazy(repositoryProvider)
    private val current by lazy(currentPathsProvider)
    private var state = JugglerSettings()
    private var dialogPanel: DialogPanel? = null

    override fun createPanel(): DialogPanel {
        val isolated = DirectoryManager.getInstance(repository).isIsolatedConfigDir(current.configDir)
        state = loadJugglerSettings(repository)
        val pathFields = mutableMapOf<IdePathKind, TextFieldWithBrowseButton>()

        return panel {
            if (isolated) {
                row { comment(message("settings.isolated.comment")) }
            }
            group(message("settings.group.ide")) {
                val installation = repository.installation
                row(message("settings.ide.name")) { label(installation.displayName) }
                row(message("settings.ide.executable")) { label(installation.executablePath) }
                row(message("settings.ide.data.dir")) { label(repository.baseDir.toString()) }
            }
            group(message("settings.group.projects")) {
                row(message("settings.main.project")) {
                    val mainProject = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir())
                        .bindText({ state.mainProjectPath }, { state.mainProjectPath = it })
                        .align(AlignX.FILL)
                        .applyToComponent {
                            (textField as? JBTextField)?.emptyText?.text = message("settings.main.project.none")
                        }
                        .validationOnApply { field -> validateMainProject(field.text)?.let { error(it) } }
                    link(message("settings.main.project.clear")) { mainProject.component.text = "" }
                }
                row(message("settings.max.recent.projects")) {
                    spinner(JugglerSettings.MAX_RECENT_RANGE)
                        .bindIntValue({ state.maxRecentProjects }, { state.maxRecentProjects = it })
                        .comment(message("settings.max.recent.projects.comment"))
                }
                row {
                    checkBox(message("settings.ask.main.project"))
                        .bindSelected({ state.askForMainProject }, { state.askForMainProject = it })
                }
            }.enabled(!isolated)
            group(message("settings.group.base")) {
                IdePathKind.entries.forEach { kind ->
                    row(pathLabel(kind)) {
                        val descriptor = if (kind.expectsDirectory) FileChooserDescriptorFactory.singleDir()
                        else FileChooserDescriptorFactory.singleFile()
                        pathFields[kind] = textFieldWithBrowseButton(descriptor)
                            .bindText({ state.basePath(kind) }, { state.setBasePath(kind, it) })
                            .align(AlignX.FILL)
                            .validationOnApply { field ->
                                validateBasePath(repository, kind, field.text)?.let { error(it) }
                            }
                            .comment(message("settings.path.current", kind.current(current)))
                            .component
                    }
                }
                row {
                    button(message("settings.use.current.paths")) {
                        val fromUi = JugglerSettings().apply {
                            pathFields.forEach { (kind, field) -> setBasePath(kind, field.text) }
                        }
                        val updated = fromUi.withCurrentIdePaths(current)
                        pathFields.forEach { (kind, field) -> field.text = updated.basePath(kind) }
                    }
                }
                row {
                    checkBox(message("settings.sync.plugins.on.update"))
                        .bindSelected({ state.syncPluginsOnIdeUpdate }, { state.syncPluginsOnIdeUpdate = it })
                }
            }.enabled(!isolated)
        }.also { dialogPanel = it }
    }

    override fun apply() {
        // Validate before the UI is pushed into the state, so a refused apply keeps the page modified
        dialogPanel?.validateAll()?.firstOrNull()?.let { throw ConfigurationException(it.message) }
        super.apply()
        val change = try {
            saveJugglerSettings(repository, state)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            LOG.warn("Failed to save Project Juggler settings", e)
            throw ConfigurationException(message("settings.error.save.failed", e.userMessage()))
        }
        change?.let(::applyMainProjectChangeInBackground)
    }

    override fun reset() {
        // Reload, so changes made meanwhile (e.g. the main project from the popup) are shown
        state = loadJugglerSettings(repository)
        super.reset()
    }

    override fun disposeUIResources() {
        super.disposeUIResources()
        dialogPanel = null
    }

    private fun applyMainProjectChangeInBackground(change: MainProjectChange) {
        val repository = repository
        application.executeOnPooledThread {
            val handler = IntelliJNotificationHandler(null)
            try {
                applyMainProjectChange(repository, change, handler)
            } catch (e: Throwable) {
                e.rethrowIfControlFlow()
                LOG.warn("Failed to change the main project from the settings page", e)
                handler.showErrorNotification(message("settings.error.main.project.failed", e.userMessage()))
            }
        }
    }

    private fun pathLabel(kind: IdePathKind): String = when (kind) {
        IdePathKind.CONFIG -> message("settings.path.config")
        IdePathKind.PLUGINS -> message("settings.path.plugins")
        IdePathKind.VM_OPTIONS -> message("settings.path.vmoptions")
    }

    companion object {
        const val ID = "com.projectjuggler.plugin.settings"
    }
}
