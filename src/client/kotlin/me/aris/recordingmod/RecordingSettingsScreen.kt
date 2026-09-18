package me.aris.recordingmod

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

// The recreated main menu from the legacy 1.12.2 mod's LiteLoader config panel. "Render
// Blueprints"/"Render Blueprint Proxies" render a blueprint's tick range to video (see
// BlueprintRenderer/VideoExporter); "Generate Markers" scans recordings for Hypixel SkyBlock
// Dungeons events (see MarkerGenerator); "Generate Blueprints From Markers" turns saved markers
// into blueprints (see BlueprintManager).
class RecordingSettingsScreen(private val parent: Screen?) : Screen(Component.literal("Recording Mod")) {
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
  private lateinit var blendFactorField: EditBox
  private lateinit var proxyRenderingWidthField: EditBox
  private lateinit var proxyRenderingHeightField: EditBox

  override fun init() {
    labels.clear()

    val fieldLabelTexts = listOf(
      "Recording Path", "Final Render Path", "Ffmpeg Path", "Rendering Width", "Rendering Height",
      "Rendering Fps", "Blend Factor", "Proxy Rendering Width", "Proxy Rendering Height"
    )
    val labelColumnWidth = fieldLabelTexts.maxOf { this.font.width(it) }
    val fieldWidth = 170
    val totalRowWidth = labelColumnWidth + 8 + fieldWidth
    val labelX = this.width / 2 - totalRowWidth / 2
    fieldColumnX = labelX + labelColumnWidth + 8

    var y = 30
    val buttonWidth = 140
    val buttonSpacing = 10
    val buttonsX = this.width / 2 - buttonWidth - buttonSpacing / 2

    addSectionHeader(buttonsX, y, "Playback")
    y += 12
    addRenderableWidget(
      Button.builder(Component.literal("Recordings")) { this.minecraft?.setScreen(RecordingsScreen(this)) }
        .bounds(buttonsX, y, buttonWidth, 20).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Markers")) { this.minecraft?.setScreen(MarkersScreen(this)) }
        .bounds(buttonsX + buttonWidth + buttonSpacing, y, buttonWidth, 20).build()
    )

    y += 30
    addSectionHeader(buttonsX, y, "Video Export")
    y += 12
    addRenderableWidget(
      Button.builder(Component.literal("Render Blueprints")) {
        BlueprintRenderer.renderAll(proxy = false)
        this.minecraft?.setScreen(null)
      }
        .tooltip(Tooltip.create(Component.literal(
          "Renders every blueprint that already has a proxy but no final render yet, to Final Render Path"
        )))
        .bounds(buttonsX, y, buttonWidth, 20).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Render Blueprint Proxies")) {
        BlueprintRenderer.renderAll(proxy = true)
        this.minecraft?.setScreen(null)
      }
        .tooltip(Tooltip.create(Component.literal("Renders a cheap preview of every blueprint that doesn't have one yet, to proxies/")))
        .bounds(buttonsX + buttonWidth + buttonSpacing, y, buttonWidth, 20).build()
    )
    y += 24
    addRenderableWidget(
      Button.builder(Component.literal("Generate Markers")) {
        val created = MarkerGenerator.generateForAllRecordings()
        this.minecraft?.player?.displayClientMessage(
          Component.literal("Generated $created marker(s) from recordings"), false
        )
      }
        .tooltip(Tooltip.create(Component.literal(
          "Scans every recording for Hypixel SkyBlock Dungeons events (deaths, drops, run start/complete/fail) and marks them"
        )))
        .bounds(buttonsX, y, buttonWidth, 20).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Generate Blueprints From Markers")) {
        val created = BlueprintManager.generateFromMarkers()
        this.minecraft?.player?.displayClientMessage(
          Component.literal("Generated $created blueprint(s) from markers"), false
        )
      }
        .tooltip(Tooltip.create(Component.literal("Creates a blueprint (20s before to 5s after) around every saved marker")))
        .bounds(buttonsX + buttonWidth + buttonSpacing, y, buttonWidth, 20).build()
    )

    y += 32
    addSectionHeader(labelX, y, "Paths")
    y += 14
    recordingPathField = addField(
      labelX, y, "Recording Path", fieldWidth, RecordingConfig.recordingPath,
      "Folder where new recordings (.rec files) are saved"
    )
    y += 24
    finalRenderPathField = addField(
      labelX, y, "Final Render Path", fieldWidth, RecordingConfig.finalRenderPath,
      "Folder where finished rendered videos will be saved"
    )
    y += 24
    ffmpegPathField = addField(
      labelX, y, "Ffmpeg Path", fieldWidth, RecordingConfig.ffmpegPath,
      "Path to the ffmpeg executable (\"ffmpeg\" if it's on your PATH) - used by the Export button on the Recordings screen"
    )

    y += 30
    addSectionHeader(labelX, y, "Rendering")
    y += 14
    renderingWidthField = addField(
      labelX, y, "Rendering Width", fieldWidth, RecordingConfig.renderingWidth.toString(),
      "Not applied yet - Export currently captures at the game's actual current window size"
    )
    y += 24
    renderingHeightField = addField(
      labelX, y, "Rendering Height", fieldWidth, RecordingConfig.renderingHeight.toString(),
      "Not applied yet - Export currently captures at the game's actual current window size"
    )
    y += 24
    renderingFpsField = addField(
      labelX, y, "Rendering Fps", fieldWidth, RecordingConfig.renderingFps.toString(),
      "Output video frame rate used by the Export button on the Recordings screen"
    )
    y += 24
    blendFactorField = addField(
      labelX, y, "Blend Factor", fieldWidth, RecordingConfig.blendFactor.toString(),
      "Not implemented yet - will control motion-blur frame blending"
    )
    y += 24
    proxyRenderingWidthField = addField(
      labelX, y, "Proxy Rendering Width", fieldWidth, RecordingConfig.proxyRenderingWidth.toString(),
      "Not applied yet - Render Blueprint Proxies currently captures at the game's actual current window size too"
    )
    y += 24
    proxyRenderingHeightField = addField(
      labelX, y, "Proxy Rendering Height", fieldWidth, RecordingConfig.proxyRenderingHeight.toString(),
      "Not applied yet - Render Blueprint Proxies currently captures at the game's actual current window size too"
    )

    addRenderableWidget(
      Button.builder(Component.literal("Done")) { onClose() }
        .bounds(this.width / 2 - 75, this.height - 28, 150, 20).build()
    )
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

  override fun onClose() {
    RecordingConfig.recordingPath = recordingPathField.value
    RecordingConfig.finalRenderPath = finalRenderPathField.value
    RecordingConfig.ffmpegPath = ffmpegPathField.value
    RecordingConfig.renderingWidth = renderingWidthField.value.toIntOrNull() ?: RecordingConfig.renderingWidth
    RecordingConfig.renderingHeight = renderingHeightField.value.toIntOrNull() ?: RecordingConfig.renderingHeight
    RecordingConfig.renderingFps = renderingFpsField.value.toIntOrNull() ?: RecordingConfig.renderingFps
    RecordingConfig.blendFactor = blendFactorField.value.toIntOrNull() ?: RecordingConfig.blendFactor
    RecordingConfig.proxyRenderingWidth =
      proxyRenderingWidthField.value.toIntOrNull() ?: RecordingConfig.proxyRenderingWidth
    RecordingConfig.proxyRenderingHeight =
      proxyRenderingHeightField.value.toIntOrNull() ?: RecordingConfig.proxyRenderingHeight
    RecordingConfig.save()

    this.minecraft?.setScreen(parent)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF)

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
}
