package me.aris.recordingmod

import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import java.io.File

// On-screen overlay: a small REC indicator while recording live, and during playback a status
// panel, a progress bar (with the I/O render range and this recording's markers on it) and the
// fixed controls list (toggled with H). Never drawn during an export, so it can't end up in the
// rendered video.
object ModHud {
  private const val PANEL_BG = 0x90000000.toInt()

  private val controls = listOf(
    "P" to "Return to menu",
    "Space" to "Play / pause",
    "Hold K" to "Slow motion",
    "A / D" to "-/+ 5 seconds",
    "F / G" to "-/+ 30 seconds",
    "Z / X" to "-/+ 10 minutes",
    "." to "Forward one frame",
    "I / O" to "Set render start / end",
    "R" to "Render proxy (makes blueprint)",
    "T" to "Timeline",
    "H" to "Hide this list"
  )

  private var markerFlashUntil = 0L

  // Marker ticks for the recording being watched - read from disk once per recording, not per frame.
  private var markerCacheFile: File? = null
  private var markerTicks: List<Int> = emptyList()

  fun flashMarker() {
    markerFlashUntil = Util.getMillis() + 1500
  }

  fun render(guiGraphics: GuiGraphics) {
    val mc = Minecraft.getInstance()
    if (VideoExporter.isBusy || mc.options.renderDebug) return
    if (PlaybackManager.active) {
      renderPlayback(guiGraphics, mc)
    } else if (RecordingManager.active) {
      renderRecording(guiGraphics, mc)
    }
  }

  private fun renderRecording(guiGraphics: GuiGraphics, mc: Minecraft) {
    val font = mc.font
    val time = PlaybackControls.formatTime(RecordingManager.currentTick)
    val marked = Util.getMillis() < markerFlashUntil
    val text = if (marked) "REC $time  Marked!" else "REC $time"
    val width = 14 + font.width(text) + 4
    guiGraphics.fill(2, 2, 2 + width, 16, PANEL_BG)
    // Blinks once a second, like a camera's record light.
    if (Util.getMillis() / 500 % 2 == 0L) {
      guiGraphics.fill(6, 6, 12, 12, 0xFFFF3030.toInt())
    }
    guiGraphics.drawString(font, text, 16, 5, if (marked) 0xFFFF55 else 0xFFFFFF)
  }

  private fun renderPlayback(guiGraphics: GuiGraphics, mc: Minecraft) {
    // The recorded player's inventory/container is up (see ReplayScreenOverlay) - keep the view as
    // close to what they saw as possible, just the thin progress bar.
    if (ReplayScreenOverlay.showing) {
      drawTimelineBar(guiGraphics, 0, guiGraphics.guiWidth(), guiGraphics.guiHeight() - 3, 3)
      return
    }
    val font = mc.font
    val file = PlaybackManager.currentFile
    val current = PlaybackManager.currentTick
    val total = PlaybackManager.totalTicks

    val status = when {
      PlaybackControls.paused -> Component.literal("❚❚ Paused").withStyle(ChatFormatting.YELLOW)
      PlaybackControls.slowMotion -> Component.literal("▶ Slow motion (¼×)").withStyle(ChatFormatting.AQUA)
      else -> Component.literal("▶ Playing").withStyle(ChatFormatting.GREEN)
    }
    val lines = mutableListOf<Component>(status)
    val timeText = PlaybackControls.formatTime(current) + (total?.let { " / " + PlaybackControls.formatTime(it) } ?: "")
    lines.add(Component.literal(timeText + (file?.let { "  " + it.nameWithoutExtension } ?: "")))

    val inTick = PlaybackControls.inTick
    val outTick = PlaybackControls.outTick
    if (inTick != null || outTick != null) {
      val inText = inTick?.let { PlaybackControls.formatTime(it) } ?: "-"
      val outText = outTick?.let { PlaybackControls.formatTime(it) } ?: "-"
      val hint = if (inTick != null && outTick != null) "  (R to render)" else ""
      lines.add(Component.literal("Render: $inText → $outText$hint").withStyle(ChatFormatting.LIGHT_PURPLE))
    }
    if (!PlaybackControls.showHelp) {
      lines.add(Component.literal("H: show controls").withStyle(ChatFormatting.GRAY))
    }
    drawPanel(guiGraphics, lines, 2, 2)

    if (PlaybackControls.showHelp) {
      val keyWidth = controls.maxOf { font.width(it.first) }
      val helpLines = mutableListOf<Component>(Component.literal("Controls").withStyle(ChatFormatting.YELLOW))
      // Pad by pixel width rather than characters, since the font isn't monospaced.
      val gap = font.width(" ")
      controls.forEach { (key, action) ->
        val padding = " ".repeat(((keyWidth - font.width(key)) / gap) + 2)
        helpLines.add(
          Component.literal(key).withStyle(ChatFormatting.WHITE)
            .append(Component.literal("$padding$action").withStyle(ChatFormatting.GRAY))
        )
      }
      val panelWidth = helpLines.maxOf { font.width(it) } + 8
      val panelHeight = helpLines.size * 10 + 6
      drawPanel(guiGraphics, helpLines, guiGraphics.guiWidth() - panelWidth - 2, (guiGraphics.guiHeight() - panelHeight) / 2)
    }

    // The timeline screen draws its own, bigger bar.
    if (mc.screen !is PlaybackTimelineScreen) {
      val width = guiGraphics.guiWidth()
      val height = guiGraphics.guiHeight()
      drawTimelineBar(guiGraphics, 0, width, height - 3, 3)
    }
  }

  private fun drawPanel(guiGraphics: GuiGraphics, lines: List<Component>, x: Int, y: Int) {
    val font = Minecraft.getInstance().font
    val width = lines.maxOf { font.width(it) } + 8
    guiGraphics.fill(x, y, x + width, y + lines.size * 10 + 6, PANEL_BG)
    lines.forEachIndexed { index, line ->
      guiGraphics.drawString(font, line, x + 4, y + 4 + index * 10, 0xFFFFFF)
    }
  }

  // Shared by the thin always-on bar here and PlaybackTimelineScreen's interactive one.
  fun drawTimelineBar(guiGraphics: GuiGraphics, x0: Int, x1: Int, y: Int, height: Int) {
    guiGraphics.fill(x0, y, x1, y + height, 0x80000000.toInt())
    val total = PlaybackManager.totalTicks ?: return
    if (total <= 0) return
    fun xAt(tick: Int) = x0 + ((tick.toFloat() / total).coerceIn(0f, 1f) * (x1 - x0)).toInt()

    guiGraphics.fill(x0, y, xAt(PlaybackManager.currentTick), y + height, 0xFF55FF55.toInt())

    val inTick = PlaybackControls.inTick
    val outTick = PlaybackControls.outTick
    if (inTick != null || outTick != null) {
      val rangeStart = xAt(inTick ?: 0)
      val rangeEnd = xAt(outTick ?: total)
      guiGraphics.fill(rangeStart, y, rangeEnd.coerceAtLeast(rangeStart + 1), y + height, 0x80C060FF.toInt())
      inTick?.let { guiGraphics.fill(xAt(it), y - 2, xAt(it) + 1, y + height, 0xFFC060FF.toInt()) }
      outTick?.let { guiGraphics.fill(xAt(it) - 1, y - 2, xAt(it), y + height, 0xFFC060FF.toInt()) }
    }

    for (tick in markersForCurrentRecording()) {
      val x = xAt(tick)
      guiGraphics.fill(x, y - 2, x + 1, y + height, 0xFFFFFF55.toInt())
    }
  }

  private fun markersForCurrentRecording(): List<Int> {
    val file = PlaybackManager.currentFile
    if (file != markerCacheFile) {
      markerCacheFile = file
      markerTicks = if (file == null) emptyList()
      else MarkerManager.list().filter { it.recordingBaseName == file.nameWithoutExtension }.map { it.tick }
    }
    return markerTicks
  }
}
