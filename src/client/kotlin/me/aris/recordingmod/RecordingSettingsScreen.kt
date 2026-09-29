package me.aris.recordingmod

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

// Settings tab of the mod menu (see RecordingModMenu) - paths and render sizes. Saved whenever
// the tab is left, whether via Done/Esc or by switching to another tab.
class RecordingSettingsScreen(parent: Screen?) : RecordingModTabScreen(MenuTab.SETTINGS, parent) {
  // Section headers and field labels drawn in render() - EditBox itself has no visible label,
  // only an accessibility-narration title, so we draw both ourselves. isHeader picks the style.
  private data class DrawnLabel(val text: String, val x: Int, val y: Int, val isHeader: Boolean)
  private val labels = mutableListOf<DrawnLabel>()
  private var fieldColumnX = 0

  private lateinit var recordingPathField: EditBox
  private lateinit var finalRenderPathField: EditBox
  private lateinit var ffmpegPathField: EditBox
  private lateinit var renderingWidthField: EditBox
  private lateinit var renderingHeightField: EditBox
  private lateinit var renderingFpsField: EditBox
  private lateinit var proxyRenderingWidthField: EditBox
  private lateinit var proxyRenderingHeightField: EditBox
  private var pixelFormat = RecordingConfig.pixelFormat
  private var videoQuality = RecordingConfig.videoQuality

  override fun init() {
    labels.clear()

    val fieldLabelTexts = listOf(
      "Recording Path", "Final Render Path", "Ffmpeg Path", "Rendering Width", "Rendering Height",
      "Rendering Fps", "Color Format", "Quality (CRF)", "Proxy Rendering Width", "Proxy Rendering Height"
    )
    val labelColumnWidth = fieldLabelTexts.maxOf { this.font.width(it) }
    val fieldWidth = 170
    val totalRowWidth = labelColumnWidth + 8 + fieldWidth
    val labelX = this.width / 2 - totalRowWidth / 2
    fieldColumnX = labelX + labelColumnWidth + 8

    addTabBar()
    var y = CONTENT_TOP
    addSectionHeader(labelX, y, "Paths")
    y += 14
    recordingPathField = addField(
      labelX, y, "Recording Path", fieldWidth, RecordingConfig.recordingPath,
      "Folder where new recordings (.rec files) are saved"
    )
    y += 22
    finalRenderPathField = addField(
      labelX, y, "Final Render Path", fieldWidth, RecordingConfig.finalRenderPath,
      "Folder where finished rendered videos will be saved"
    )
    y += 22
    ffmpegPathField = addField(
      labelX, y, "Ffmpeg Path", fieldWidth, RecordingConfig.ffmpegPath,
      "Path to the ffmpeg executable (\"ffmpeg\" if it's on your PATH) - used for every video render"
    )

    y += 24
    addSectionHeader(labelX, y, "Rendering")
    y += 14
    renderingWidthField = addField(
      labelX, y, "Rendering Width", fieldWidth, RecordingConfig.renderingWidth.toString(),
      "Output video width in pixels - the game window is resized to this for the duration of an Export/final render"
    )
    y += 22
    renderingHeightField = addField(
      labelX, y, "Rendering Height", fieldWidth, RecordingConfig.renderingHeight.toString(),
      "Output video height in pixels - the game window is resized to this for the duration of an Export/final render"
    )
    y += 22
    renderingFpsField = addField(
      labelX, y, "Rendering Fps", fieldWidth, RecordingConfig.renderingFps.toString(),
      "Output video frame rate used for every video render"
    )
    y += 22
    pixelFormat = RecordingConfig.pixelFormat
    labels.add(DrawnLabel("Color Format", labelX, y, isHeader = false))
    addRenderableWidget(
      CycleButton.builder<String> { Component.literal(it) }
        .withValues(RecordingConfig.PIXEL_FORMATS)
        .withInitialValue(pixelFormat.takeIf { it in RecordingConfig.PIXEL_FORMATS } ?: "yuv420p")
        .displayOnlyValue()
        .withTooltip { Tooltip.create(Component.literal(
          "yuv420p: most compatible. yuv444p: full colour resolution, sharper coloured edges, " +
            "bigger files - some players/editors can't play it"
        )) }
        .create(fieldColumnX, y, fieldWidth, 18, Component.literal("Color Format")) { _, value -> pixelFormat = value }
    )
    y += 22
    videoQuality = RecordingConfig.videoQuality
    labels.add(DrawnLabel("Quality (CRF)", labelX, y, isHeader = false))
    addRenderableWidget(QualitySlider(fieldColumnX, y, fieldWidth))
    y += 22
    proxyRenderingWidthField = addField(
      labelX, y, "Proxy Rendering Width", fieldWidth, RecordingConfig.proxyRenderingWidth.toString(),
      "Output width in pixels for quick low-effort proxy renders - the window is resized to this instead while proxy-rendering"
    )
    y += 22
    proxyRenderingHeightField = addField(
      labelX, y, "Proxy Rendering Height", fieldWidth, RecordingConfig.proxyRenderingHeight.toString(),
      "Output height in pixels for quick low-effort proxy renders - the window is resized to this instead while proxy-rendering"
    )

    addDoneButton()
  }

  private fun addSectionHeader(x: Int, y: Int, text: String) {
    labels.add(DrawnLabel(text, x, y, isHeader = true))
  }

  private fun addField(
    labelX: Int, y: Int, label: String, fieldWidth: Int, initialValue: String, tooltip: String
  ): EditBox {
    labels.add(DrawnLabel(label, labelX, y, isHeader = false))
    val field = EditBox(this.font, fieldColumnX, y, fieldWidth, 18, Component.literal(label))
    field.setMaxLength(1024)
    field.value = initialValue
    field.setTooltip(Tooltip.create(Component.literal(tooltip)))
    addRenderableWidget(field)
    return field
  }

  override fun save() {
    RecordingConfig.recordingPath = recordingPathField.value
    RecordingConfig.finalRenderPath = finalRenderPathField.value
    RecordingConfig.ffmpegPath = ffmpegPathField.value
    RecordingConfig.renderingWidth = renderingWidthField.value.toIntOrNull() ?: RecordingConfig.renderingWidth
    RecordingConfig.renderingHeight = renderingHeightField.value.toIntOrNull() ?: RecordingConfig.renderingHeight
    RecordingConfig.renderingFps = renderingFpsField.value.toIntOrNull() ?: RecordingConfig.renderingFps
    RecordingConfig.proxyRenderingWidth =
      proxyRenderingWidthField.value.toIntOrNull() ?: RecordingConfig.proxyRenderingWidth
    RecordingConfig.proxyRenderingHeight =
      proxyRenderingHeightField.value.toIntOrNull() ?: RecordingConfig.proxyRenderingHeight
    RecordingConfig.pixelFormat = pixelFormat
    RecordingConfig.videoQuality = videoQuality
    RecordingConfig.save()
  }

  // x264 CRF 0..MAX_CRF - lower is sharper and bigger (0 = lossless).
  private inner class QualitySlider(x: Int, y: Int, width: Int) :
    AbstractSliderButton(x, y, width, 18, Component.empty(), videoQuality / MAX_CRF.toDouble()) {
    init {
      updateMessage()
      setTooltip(Tooltip.create(Component.literal("Video compression (x264 CRF) - lower is sharper but bigger, 0 is lossless")))
    }

    override fun updateMessage() {
      message = Component.literal(if (videoQuality == 0) "0 (lossless)" else videoQuality.toString())
    }

    override fun applyValue() {
      videoQuality = Math.round(value * MAX_CRF).toInt()
    }
  }

  override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    for (label in labels) {
      if (label.isHeader) {
        guiGraphics.drawString(
          this.font, Component.literal(label.text).withStyle(ChatFormatting.YELLOW), label.x, label.y, 0xFFFFFF
        )
      } else {
        val labelEndX = fieldColumnX - 8
        guiGraphics.drawString(
          this.font, label.text, labelEndX - this.font.width(label.text), label.y + 5, 0xA0A0A0
        )
      }
    }
  }

  private companion object {
    const val MAX_CRF = 30
  }
}
