package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File
import kotlin.math.sign

// Lists saved markers (see MarkerManager) so you can jump straight to a bookmarked moment in a
// recording instead of scrubbing through it manually. Scrollable with the mouse wheel once there
// are more than fit on screen.
class MarkersScreen(parent: Screen?) : RecordingModTabScreen(MenuTab.MARKERS, parent) {
  private var markers: List<MarkerManager.Marker> = emptyList()
  private var scrollOffset = 0
  private var maxVisible = 1

  private val buttonWidth = 320
  private val jumpButtonWidth = 256
  private val renameButtonWidth = buttonWidth - jumpButtonWidth - 4
  private val buttonHeight = 20
  private val spacing = 4
  private val startY = CONTENT_TOP + 34

  override fun init() {
    markers = MarkerManager.list()
    maxVisible = ((this.height - startY - 34) / (buttonHeight + spacing)).coerceAtLeast(1)
    scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())

    rebuildList()
  }

  private fun maxScrollOffset() = (markers.size - maxVisible).coerceAtLeast(0)

  private fun rebuildList() {
    clearWidgets()
    addTabBar()

    val actionWidth = (buttonWidth - 4) / 2
    val actionsX = this.width / 2 - buttonWidth / 2
    addRenderableWidget(
      Button.builder(Component.literal("Generate From Recordings")) {
        val created = MarkerGenerator.generateForAllRecordings()
        this.minecraft?.setScreen(MarkersScreen(parent))
        RecordingModMenu.toast("Generated $created marker(s)")
      }
        .tooltip(Tooltip.create(Component.literal(
          "Scans every recording for Hypixel SkyBlock Dungeons events (deaths, drops, run start/complete/fail) and marks them"
        )))
        .bounds(actionsX, CONTENT_TOP, actionWidth, buttonHeight).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Make Blueprints")) {
        val created = BlueprintManager.generateFromMarkers()
        RecordingModMenu.toast("Created $created blueprint(s)")
      }
        .tooltip(Tooltip.create(Component.literal(
          "Creates a blueprint (20s before to 5s after) around every marker - see the Blueprints tab"
        )))
        .bounds(actionsX + actionWidth + 4, CONTENT_TOP, actionWidth, buttonHeight).build()
    )

    if (markers.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No markers found")) {}
          .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
          .build()
      ).active = false
    }

    markers.drop(scrollOffset).take(maxVisible).forEachIndexed { index, marker ->
      val label = "${marker.name} (${marker.recordingBaseName}, tick ${marker.tick})"
      val rowX = this.width / 2 - buttonWidth / 2
      val rowY = startY + index * (buttonHeight + spacing)
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
        }.bounds(rowX, rowY, jumpButtonWidth, buttonHeight).build()
      )
      addRenderableWidget(
        Button.builder(Component.literal("Rename")) {
          this.minecraft?.setScreen(RenameMarkerScreen(marker, this))
        }.bounds(rowX + jumpButtonWidth + 4, rowY, renameButtonWidth, buttonHeight).build()
      )
    }

    addDoneButton()
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

  override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    if (markers.size > maxVisible) {
      val first = scrollOffset + 1
      val last = (scrollOffset + maxVisible).coerceAtMost(markers.size)
      guiGraphics.drawCenteredString(
        this.font, "$first-$last of ${markers.size} (scroll for more)", this.width / 2, startY - 10, 0xA0A0A0
      )
    }
  }
}
