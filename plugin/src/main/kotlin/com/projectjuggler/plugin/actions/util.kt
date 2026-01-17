package com.projectjuggler.plugin.actions

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.plugin.services.IdeInstallationService

val currentIdeConfigRepository: IdeConfigRepository
    get() = IdeInstallationService.getInstance().currentRepository
