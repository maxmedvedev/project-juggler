@file:Suppress("UseJBColor") // The Dock icon is painted once, outside any theme

package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.nio.file.Path

/**
 * The text this IDE draws on its macOS Dock icon, a project display name ([ProjectPath.name]):
 * - in an isolated instance ([projectId] set), its project; null when the project has no metadata;
 * - in the main instance ([currentConfigDir] is the base config), the main project; null when none is set.
 *
 * Null in any other IDE (not isolated, not on the base config) and when `showProjectNameOnDockIcon` is off.
 */
internal fun dockIconLabel(repository: IdeConfigRepository, projectId: ProjectId?, currentConfigDir: Path): String? {
    val config = repository.load()
    if (!config.showProjectNameOnDockIcon) return null
    if (projectId != null) return repository.loadProjectMetadata(projectId)?.path?.name
    if (!MainProjectService.isRunningInMainInstance(repository, currentConfigDir)) return null
    return config.mainProjectPath?.let { ProjectPath(it).name }
}

/** The label as drawn: [text] (possibly ellipsized) at [fontSize]. */
internal data class FittedLabel(val text: String, val fontSize: Float)

/**
 * Fits [label] into [maxWidth]: the largest font size from [maxFontSize] down to [minFontSize] at which it fits,
 * otherwise the longest prefix plus `…` at [minFontSize]. [width] measures a text at a font size.
 */
internal fun fitLabel(
    label: String,
    maxWidth: Int,
    maxFontSize: Float,
    minFontSize: Float,
    width: (text: String, fontSize: Float) -> Int,
): FittedLabel {
    var size = maxFontSize
    while (size >= minFontSize) {
        if (width(label, size) <= maxWidth) return FittedLabel(label, size)
        size -= FONT_SIZE_STEP
    }
    val prefixLength = (label.length - 1 downTo 1).firstOrNull { length ->
        width(ellipsize(label, length), minFontSize) <= maxWidth
    }
    return FittedLabel(prefixLength?.let { ellipsize(label, it) } ?: ELLIPSIS, minFontSize)
}

/**
 * A copy of [base] with [label] in white on a dark rounded banner across the top. Only the banner area changes;
 * [base] is left as is.
 */
internal fun renderLabeledIcon(base: BufferedImage, label: String): BufferedImage {
    val width = base.width
    val height = base.height
    val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = result.createGraphics()
    try {
        g.drawImage(base, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val left = width * BANNER_INSET
        val top = height * BANNER_INSET
        val bannerWidth = width - 2 * left
        val bannerHeight = height * BANNER_HEIGHT
        val arc = bannerHeight * 0.5
        g.color = BANNER_COLOR
        g.fill(RoundRectangle2D.Double(left, top, bannerWidth, bannerHeight, arc, arc))

        val font = Font(Font.SANS_SERIF, Font.BOLD, 1)
        val padding = bannerHeight * TEXT_PADDING
        val fitted = fitLabel(
            label,
            maxWidth = (bannerWidth - 2 * padding).toInt(),
            maxFontSize = (bannerHeight * MAX_FONT_RATIO).toFloat(),
            minFontSize = (bannerHeight * MIN_FONT_RATIO).toFloat(),
        ) { text, size -> g.getFontMetrics(font.deriveFont(size)).stringWidth(text) }

        g.font = font.deriveFont(fitted.fontSize)
        val metrics = g.fontMetrics
        val x = left + (bannerWidth - metrics.stringWidth(fitted.text)) / 2
        val y = top + (bannerHeight - metrics.height) / 2 + metrics.ascent
        g.color = Color.WHITE
        g.drawString(fitted.text, x.toFloat(), y.toFloat())
    } finally {
        g.dispose()
    }
    return result
}

/** [image] as a [BufferedImage]: its largest resolution variant when it has several (the Dock icon has 128 and 256 px). */
internal fun largestVariant(image: Image): BufferedImage {
    val largest = (image as? MultiResolutionImage)?.resolutionVariants?.maxByOrNull { it.getWidth(null) } ?: image
    if (largest is BufferedImage) return largest
    val result = BufferedImage(largest.getWidth(null), largest.getHeight(null), BufferedImage.TYPE_INT_ARGB)
    val g = result.createGraphics()
    try {
        g.drawImage(largest, 0, 0, null)
    } finally {
        g.dispose()
    }
    return result
}

private const val BANNER_INSET = 0.06
private const val BANNER_HEIGHT = 0.22

/** Fraction of the icon height down to the banner's bottom edge; [renderLabeledIcon] changes nothing below it. */
internal const val BANNER_BOTTOM = BANNER_INSET + BANNER_HEIGHT

private fun ellipsize(label: String, length: Int) = label.take(length).trimEnd() + ELLIPSIS

private const val ELLIPSIS = "…"
private const val FONT_SIZE_STEP = 0.5f
private const val TEXT_PADDING = 0.25
private const val MAX_FONT_RATIO = 0.6
private const val MIN_FONT_RATIO = 0.4
private val BANNER_COLOR = Color(0, 0, 0, 190)
