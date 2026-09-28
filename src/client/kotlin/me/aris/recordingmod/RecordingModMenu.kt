package me.aris.recordingmod

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

enum class MenuTab(val label: String) {
  RECORDINGS("Recordings"),
  MARKERS("Markers"),
  BLUEPRINTS("Blueprints"),
  SETTINGS("Settings")
}

// The mod's one menu, opened from the record icon button on the title and pause screens - each
// tab is its own Screen sharing the tab bar below, and switching tabs keeps the same parent so
// Done/Esc always goes back to wherever the menu was opened from.
object RecordingModMenu {
  fun open(tab: MenuTab, parent: Screen?): Screen = when (tab) {
    MenuTab.RECORDINGS -> RecordingsScreen(parent)
    MenuTab.MARKERS -> MarkersScreen(parent)
    MenuTab.BLUEPRINTS -> BlueprintsScreen(parent)
    MenuTab.SETTINGS -> RecordingSettingsScreen(parent)
  }

  // Short confirmation popup for menu actions - chat messages aren't visible from the title screen.
  fun toast(text: String) {
    SystemToast.addOrUpdate(
      Minecraft.getInstance().toasts, SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
      Component.literal("Recording Mod"), Component.literal(text)
    )
  }
}

abstract class RecordingModTabScreen(private val tab: MenuTab, protected val parent: Screen?) :
  Screen(Component.literal("Recording Mod")) {
  companion object {
    // First y below the tab bar that tab content may use.
    const val CONTENT_TOP = 36
    private const val TAB_WIDTH = 84
    private const val TAB_GAP = 2
  }

  private fun tabX(index: Int): Int {
    val count = MenuTab.values().size
    val total = count * TAB_WIDTH + (count - 1) * TAB_GAP
    return this.width / 2 - total / 2 + index * (TAB_WIDTH + TAB_GAP)
  }

  // Call after every clearWidgets() - the tab bar is just ordinary widgets.
  protected fun addTabBar() {
    MenuTab.values().forEachIndexed { index, other ->
      val label = if (other == tab) Component.literal(other.label).withStyle(ChatFormatting.YELLOW)
      else Component.literal(other.label)
      addRenderableWidget(
        Button.builder(label) { if (other != tab) switchTo(other) }
          .bounds(tabX(index), 6, TAB_WIDTH, 20).build()
      )
    }
  }

  protected fun addDoneButton() {
    addRenderableWidget(
      Button.builder(Component.literal("Done")) { onClose() }
        .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build()
    )
  }

  // Hook for tabs with unsaved state (Settings) - runs before leaving the tab either way.
  protected open fun save() {}

  private fun switchTo(other: MenuTab) {
    save()
    this.minecraft?.setScreen(RecordingModMenu.open(other, parent))
  }

  override fun onClose() {
    save()
    this.minecraft?.setScreen(parent)
  }

  protected open fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)

    val activeIndex = MenuTab.values().indexOf(tab)
    guiGraphics.fill(tabX(activeIndex), 27, tabX(activeIndex) + TAB_WIDTH, 29, 0xFFFFFF55.toInt())

    renderContent(guiGraphics, mouseX, mouseY, partialTick)

    val status = when {
      BlueprintRenderer.running || VideoExporter.isBusy -> "Rendering..."
      RecordingManager.active -> "Recording: ${RecordingManager.currentFile?.nameWithoutExtension}"
      else -> "Not recording"
    }
    // Only where it fits beside the centered Done button.
    if (this.font.width(status) + 8 < this.width / 2 - 100) {
      guiGraphics.drawString(this.font, status, 4, this.height - 20, 0x808080)
    }
  }
}
