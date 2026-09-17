package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File

// Lists saved markers (see MarkerManager) so you can jump straight to a bookmarked moment in a
// recording instead of scrubbing through it manually.
class MarkersScreen(private val parent: Screen?) : Screen(Component.literal("Markers")) {
  override fun init() {
    val markers = MarkerManager.list()

    val buttonWidth = 320
    val buttonHeight = 20
    val spacing = 4
    val startY = 40
    val maxVisible = ((this.height - startY - 40) / (buttonHeight + spacing)).coerceAtLeast(1)

    if (markers.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No markers found")) {}
          .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
          .build()
      ).active = false
    }

    markers.take(maxVisible).forEachIndexed { index, marker ->
      val label = "${marker.name} (${marker.recordingBaseName}, tick ${marker.tick})"
      addRenderableWidget(
        Button.builder(Component.literal(label)) {
          val recordingFile = File(RecordingConfig.recordingsDir, "${marker.recordingBaseName}.rec")
          if (!recordingFile.exists()) {
            this.minecraft?.player?.displayClientMessage(
              Component.literal("Recording \"${marker.recordingBaseName}\" no longer exists"), false
            )
            return@builder
          }
          PlaybackManager.startAtTick(recordingFile, marker.tick)
          this.minecraft?.setScreen(null)
        }.bounds(
          this.width / 2 - buttonWidth / 2,
          startY + index * (buttonHeight + spacing),
          buttonWidth,
          buttonHeight
        ).build()
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
