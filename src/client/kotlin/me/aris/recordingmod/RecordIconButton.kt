package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import kotlin.math.sqrt

// 20x20 icon button that opens the mod menu - a normal button background with a red "record" dot
// drawn on it, like vanilla's language/accessibility icon buttons. Drawn with fills rather than a
// texture so it stays crisp at every GUI scale.
class RecordIconButton(x: Int, y: Int, onPress: OnPress) :
  Button(x, y, 20, 20, Component.empty(), onPress, DEFAULT_NARRATION) {
  init {
    setTooltip(Tooltip.create(Component.literal("Recording Mod")))
  }

  override fun createNarrationMessage() = Component.literal("Recording Mod")

  override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    super.renderWidget(guiGraphics, mouseX, mouseY, partialTick)
    val centerX = x + width / 2
    val centerY = y + height / 2
    drawDisc(guiGraphics, centerX, centerY, 6f, 0xFF3A0000.toInt())
    drawDisc(guiGraphics, centerX, centerY, 5f, if (isHoveredOrFocused) 0xFFFF4A4A.toInt() else 0xFFE02020.toInt())
  }

  // One horizontal fill per pixel row - a circle from a stack of spans.
  private fun drawDisc(guiGraphics: GuiGraphics, centerX: Int, centerY: Int, radius: Float, color: Int) {
    val r = radius.toInt()
    for (dy in -r until r) {
      val rowCenter = dy + 0.5f
      val half = sqrt((radius * radius - rowCenter * rowCenter).coerceAtLeast(0f))
      val halfWidth = (half + 0.5f).toInt()
      if (halfWidth > 0) guiGraphics.fill(centerX - halfWidth, centerY + dy, centerX + halfWidth, centerY + dy + 1, color)
    }
  }
}
