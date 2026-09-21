package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

private const val TICKS_PER_SECOND = 20

// Transient overlay opened by the "open timeline" keybind while watching a replay - lets you
// click or drag a visual bar to scrub, instead of scroll notches or fixed skip keybinds. Doesn't
// darken/pause the game (isPauseScreen() = false, no renderBackground) since it's meant to sit on
// top of the replay while it keeps playing, same as RenameRecordingScreen/RenameMarkerScreen do.
class PlaybackTimelineScreen : Screen(Component.literal("Timeline")) {
  private val barInset = 20
  private val barHeight = 8
  private var dragging = false

  // A backward seek fully rebuilds the fake world (see PlaybackManager.seekTo) - calling that once
  // per mouseDragged frame while dragging backward restarted it dozens of times a second, which is
  // what was actually behind missing chunks/animations and low FPS while scrubbing, not a single
  // bug in each of those systems. Throttling to 10/sec while dragging, with one final exact seek on
  // release, keeps the drag responsive without hammering the world rebuild.
  private var lastDragSeekMillis = 0L
  private val dragSeekIntervalMillis = 100L

  private fun barY() = this.height - 40
  private fun barX0() = barInset
  private fun barX1() = this.width - barInset

  private fun fractionAt(mouseX: Double): Float {
    val x0 = barX0()
    val x1 = barX1()
    if (x1 <= x0) return 0f
    return (((mouseX - x0) / (x1 - x0)).toFloat()).coerceIn(0f, 1f)
  }

  private fun seekToFraction(mouseX: Double) {
    val total = PlaybackManager.totalTicks ?: return
    if (!PlaybackManager.active) return
    PlaybackManager.seekTo((fractionAt(mouseX) * total).toInt())
  }

  override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
    val onBar = button == 0 && mouseX >= barX0() && mouseX <= barX1() &&
      mouseY >= barY() - 4 && mouseY <= barY() + barHeight + 4
    if (onBar && PlaybackManager.totalTicks != null) {
      dragging = true
      lastDragSeekMillis = System.currentTimeMillis()
      seekToFraction(mouseX)
      return true
    }
    return super.mouseClicked(mouseX, mouseY, button)
  }

  override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
    if (dragging) {
      val now = System.currentTimeMillis()
      if (now - lastDragSeekMillis >= dragSeekIntervalMillis) {
        lastDragSeekMillis = now
        seekToFraction(mouseX)
      }
      return true
    }
    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
  }

  override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
    if (dragging) {
      dragging = false
      seekToFraction(mouseX)
    }
    return super.mouseReleased(mouseX, mouseY, button)
  }

  override fun onClose() {
    this.minecraft?.setScreen(null)
  }

  override fun isPauseScreen() = false

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    // Deliberately skips super.render()/renderBackground() - there's nothing else on this screen
    // to render, and both would darken the replay behind the bar.
    val x0 = barX0()
    val x1 = barX1()
    val y = barY()
    val total = PlaybackManager.totalTicks
    val current = PlaybackManager.currentTick

    // A plain translucent fill nearly disappears against a bright world - an outline makes the
    // bar findable even when it has nothing to show yet (no total, see below).
    guiGraphics.fill(x0 - 1, y - 1, x1 + 1, y + barHeight + 1, 0xFFFFFFFF.toInt())
    guiGraphics.fill(x0, y, x1, y + barHeight, 0x80000000.toInt())

    val label = if (total != null) {
      val fraction = (current.toFloat() / total).coerceIn(0f, 1f)
      val fillX = x0 + (fraction * (x1 - x0)).toInt()
      guiGraphics.fill(x0, y, fillX, y + barHeight, 0xFF55FF55.toInt())
      guiGraphics.fill((fillX - 1).coerceIn(x0, x1 - 2), y - 2, (fillX + 1).coerceIn(x0 + 2, x1), y + barHeight + 2, 0xFFFFFFFF.toInt())
      "${formatTime(current)} / ${formatTime(total)}  (click or drag the bar)"
    } else {
      "${formatTime(current)} - no duration data for this recording, can't scrub (record something new to enable this)"
    }

    guiGraphics.drawCenteredString(this.font, label, this.width / 2, y - 14, 0xFFFFFF)
  }

  private fun formatTime(ticks: Int): String {
    val totalSeconds = ticks / TICKS_PER_SECOND
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
  }
}
