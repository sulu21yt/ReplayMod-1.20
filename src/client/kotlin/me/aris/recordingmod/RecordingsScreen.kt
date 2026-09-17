package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

// A simple list of past recordings so you can watch one back later, not just the most recent one.
class RecordingsScreen(private val parent: Screen?) : Screen(Component.literal("Recordings")) {
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  override fun init() {
    val files = RecordingConfig.recordingsDir.listFiles { f -> f.extension == "rec" }
      ?.sortedByDescending { it.lastModified() }
      ?: emptyList()

    val buttonWidth = 320
    val playButtonWidth = 244
    val exportButtonWidth = buttonWidth - playButtonWidth - 4
    val buttonHeight = 20
    val spacing = 4
    val startY = 40
    val maxVisible = ((this.height - startY - 40) / (buttonHeight + spacing)).coerceAtLeast(1)

    if (files.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No recordings found")) {}
          .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
          .build()
      ).active = false
    }

    files.take(maxVisible).forEachIndexed { index, file ->
      val label = "${file.nameWithoutExtension} (${dateFormat.format(Date(file.lastModified()))})"
      val rowX = this.width / 2 - buttonWidth / 2
      val rowY = startY + index * (buttonHeight + spacing)
      addRenderableWidget(
        Button.builder(Component.literal(label)) {
          PlaybackManager.start(file)
          this.minecraft?.setScreen(null)
        }.bounds(rowX, rowY, playButtonWidth, buttonHeight).build()
      )
      addRenderableWidget(
        Button.builder(Component.literal("Export")) {
          val output = File(RecordingConfig.finalRenderPath, "${file.nameWithoutExtension}.mp4")
          // Only close the screen if the export actually started - if ffmpeg failed to launch,
          // PlaybackManager never started either, and closing anyway would leave the player
          // staring at a blank world with no screen at all.
          if (VideoExporter.start(file, output)) {
            this.minecraft?.setScreen(null)
          }
        }.bounds(rowX + playButtonWidth + 4, rowY, exportButtonWidth, buttonHeight).build()
      )
    }

    addRenderableWidget(
      Button.builder(Component.literal("Cancel")) { onClose() }
        .bounds(this.width / 2 - 100, this.height - 30, 200, 20)
        .build()
    )
  }

  override fun onClose() {
    this.minecraft?.setScreen(parent)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF)
  }
}
