package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File
import kotlin.math.sign

// Lists saved markers (see MarkerManager) so you can jump straight to a bookmarked moment in a
// recording instead of scrubbing through it manually. Scrollable with the mouse wheel once there
// are more than fit on screen.
class MarkersScreen(private val parent: Screen?) : Screen(Component.literal("Markers")) {
  private var markers: List<MarkerManager.Marker> = emptyList()
  private var scrollOffset = 0
  private var maxVisible = 1

  private val buttonWidth = 320
  private val buttonHeight = 20
  private val spacing = 4
  private val startY = 40

  override fun init() {
    markers = MarkerManager.list()
    maxVisible = ((this.height - startY - 40) / (buttonHeight + spacing)).coerceAtLeast(1)
    scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())

    rebuildList()
  }

  private fun maxScrollOffset() = (markers.size - maxVisible).coerceAtLeast(0)

  private fun rebuildList() {
    clearWidgets()

    if (markers.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No markers found")) {}
          .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
          .build()
      ).active = false
    }

    markers.drop(scrollOffset).take(maxVisible).forEachIndexed { index, marker ->
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

  override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
    if (maxScrollOffset() == 0) return super.mouseScrolled(mouseX, mouseY, delta)
    val newOffset = (scrollOffset - delta.sign.toInt()).coerceIn(0, maxScrollOffset())
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset
      rebuildList()
    }
    return true
  }

  override fun onClose() {
    this.minecraft?.setScreen(parent)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF)

    if (markers.size > maxVisible) {
      val first = scrollOffset + 1
      val last = (scrollOffset + maxVisible).coerceAtMost(markers.size)
      guiGraphics.drawCenteredString(
        this.font, "$first-$last of ${markers.size} (scroll for more)", this.width / 2, 27, 0xA0A0A0
      )
    }
  }
}
