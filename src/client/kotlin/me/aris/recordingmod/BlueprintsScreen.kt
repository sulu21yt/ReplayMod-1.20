package me.aris.recordingmod

import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File
import kotlin.math.sign

// Blueprints tab of the mod menu - the review step of the proxy workflow (see BlueprintManager):
// render cheap proxies, watch them, delete the ones that didn't turn out, final-render the rest.
class BlueprintsScreen(parent: Screen?) : RecordingModTabScreen(MenuTab.BLUEPRINTS, parent) {
  private var blueprints: List<BlueprintManager.Blueprint> = emptyList()
  private var scrollOffset = 0
  private var maxVisible = 1

  private val rowWidth = 400
  private val labelWidth = 196
  private val smallButtonWidth = 48
  private val deleteButtonWidth = 40
  private val buttonHeight = 20
  private val spacing = 4
  private val startY = CONTENT_TOP + 34

  // Status column text per visible row, drawn in renderContent.
  private data class RowLabel(val text: Component, val status: Component, val y: Int)
  private val rowLabels = mutableListOf<RowLabel>()

  override fun init() {
    blueprints = BlueprintManager.list()
    maxVisible = ((this.height - startY - 34) / (buttonHeight + spacing)).coerceAtLeast(1)
    scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())
    rebuildList()
  }

  private fun maxScrollOffset() = (blueprints.size - maxVisible).coerceAtLeast(0)

  private fun rowX() = this.width / 2 - rowWidth / 2

  private fun busy() = BlueprintRenderer.running || VideoExporter.isBusy

  private fun rebuildList() {
    clearWidgets()
    rowLabels.clear()
    addTabBar()

    val actionWidth = (rowWidth - 3 * 4) / 4
    val actions = listOf(
      Triple("Render Proxies", "Renders a quick preview of every blueprint that doesn't have one yet, to proxies/") {
        BlueprintRenderer.renderAll(proxy = true)
        this.minecraft?.setScreen(null)
      },
      Triple("Render Finals", "Final-renders every blueprint that has a proxy but no final video yet, to Final Render Path") {
        BlueprintRenderer.renderAll(proxy = false)
        this.minecraft?.setScreen(null)
      },
      Triple("Proxies Folder", "Opens the proxies/ folder") { openFolder(File("proxies")) },
      Triple("Finals Folder", "Opens the Final Render Path folder") { openFolder(File(RecordingConfig.finalRenderPath)) }
    )
    actions.forEachIndexed { index, (label, tooltip, action) ->
      val button = addRenderableWidget(
        Button.builder(Component.literal(label)) { action() }
          .tooltip(Tooltip.create(Component.literal(tooltip)))
          .bounds(rowX() + index * (actionWidth + 4), CONTENT_TOP, actionWidth, buttonHeight).build()
      )
      if (index < 2) button.active = !busy()
    }

    if (blueprints.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No blueprints - press R while watching, or use Markers > Make Blueprints")) {}
          .bounds(rowX(), startY, rowWidth, buttonHeight).build()
      ).active = false
    }

    blueprints.drop(scrollOffset).take(maxVisible).forEachIndexed { index, blueprint ->
      val y = startY + index * (buttonHeight + spacing)
      val hasProxy = BlueprintManager.hasProxy(blueprint)
      val hasFinal = BlueprintManager.hasFinal(blueprint)

      val seconds = (blueprint.endTick - blueprint.startTick) / 20
      rowLabels.add(
        RowLabel(
          Component.literal(
            "${PlaybackControls.formatTime(blueprint.startTick)}-${PlaybackControls.formatTime(blueprint.endTick)} " +
              "(${seconds}s) ${blueprint.recordingBaseName}"
          ),
          Component.literal("Proxy").withStyle(if (hasProxy) ChatFormatting.GREEN else ChatFormatting.DARK_GRAY)
            .append(Component.literal(" Final").withStyle(if (hasFinal) ChatFormatting.GREEN else ChatFormatting.DARK_GRAY)),
          y
        )
      )

      var x = rowX() + labelWidth
      addRenderableWidget(
        Button.builder(Component.literal("Watch")) {
          val video = if (hasFinal) BlueprintManager.finalFile(blueprint) else BlueprintManager.proxyFile(blueprint)
          Util.getPlatform().openFile(video)
        }
          .tooltip(Tooltip.create(Component.literal(if (hasFinal) "Opens the final video" else "Opens the proxy video")))
          .bounds(x, y, smallButtonWidth, buttonHeight).build()
      ).active = hasProxy || hasFinal
      x += smallButtonWidth + spacing

      addRenderableWidget(
        Button.builder(Component.literal("Proxy")) {
          BlueprintRenderer.render(listOf(blueprint), proxy = true)
          this.minecraft?.setScreen(null)
        }
          .tooltip(Tooltip.create(Component.literal(if (hasProxy) "Re-render the proxy" else "Render a quick proxy")))
          .bounds(x, y, smallButtonWidth, buttonHeight).build()
      ).active = !busy()
      x += smallButtonWidth + spacing

      addRenderableWidget(
        Button.builder(Component.literal("Final")) {
          BlueprintRenderer.render(listOf(blueprint), proxy = false)
          this.minecraft?.setScreen(null)
        }
          .tooltip(Tooltip.create(Component.literal("Render the full-quality video")))
          .bounds(x, y, smallButtonWidth, buttonHeight).build()
      ).active = !busy()
      x += smallButtonWidth + spacing

      addRenderableWidget(
        Button.builder(Component.literal("Delete").withStyle(ChatFormatting.RED)) { confirmDelete(blueprint) }
          .tooltip(Tooltip.create(Component.literal("Deletes the blueprint and its proxy (a final video is kept)")))
          .bounds(x, y, deleteButtonWidth, buttonHeight).build()
      )
    }

    addDoneButton()
  }

  private fun openFolder(folder: File) {
    folder.mkdirs()
    Util.getPlatform().openFile(folder)
  }

  private fun confirmDelete(blueprint: BlueprintManager.Blueprint) {
    this.minecraft?.setScreen(
      ConfirmScreen(
        { confirmed ->
          if (confirmed) {
            blueprint.file.delete()
            BlueprintManager.proxyFile(blueprint).delete()
          }
          this.minecraft?.setScreen(BlueprintsScreen(parent))
        },
        Component.literal("Delete this blueprint?"),
        Component.literal("${blueprint.baseName}\nIts proxy video is deleted too. A final video is kept.")
      )
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

  override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    for (row in rowLabels) {
      guiGraphics.drawString(this.font, row.text, rowX(), row.y + 2, 0xFFFFFF)
      guiGraphics.drawString(this.font, row.status, rowX(), row.y + 11, 0xFFFFFF)
    }

    if (blueprints.size > maxVisible) {
      val first = scrollOffset + 1
      val last = (scrollOffset + maxVisible).coerceAtMost(blueprints.size)
      guiGraphics.drawCenteredString(
        this.font, "$first-$last of ${blueprints.size} (scroll for more)", this.width / 2, startY - 10, 0xA0A0A0
      )
    }
  }
}
