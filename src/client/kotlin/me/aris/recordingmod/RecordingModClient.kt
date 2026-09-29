package me.aris.recordingmod

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

// No KeyMappings at all (nothing in the Controls menu): recording starts on its own whenever you
// join a world, the menu opens from a button on the title/pause screen, marking a moment is a
// fixed L key, and playback has its own fixed keys (see PlaybackControls).
object RecordingModClient : ClientModInitializer {
  val LOGGER = LoggerFactory.getLogger("recordingmod")

  private const val TICKS_PER_SECOND = 20

  override fun onInitializeClient() {
    RecordingConfig.load()

    // Recording starts on its own as the login packet arrives (see RecordingManager.onPacketReceived)
    // and stops when the connection goes away or a replay starts (see PlaybackManager.start).
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
      if (RecordingManager.active && !PlaybackManager.active) RecordingManager.stop()
    }

    ClientTickEvents.END_CLIENT_TICK.register { mc ->
      // Backup for leaving a world without a disconnect event. currentTick only counts ticks with a
      // player, so this can't fire in the gap between login and the world actually existing.
      if (RecordingManager.active && mc.level == null && RecordingManager.currentTick > 0) {
        RecordingManager.stop()
      }

      RecordingManager.onClientTick()
      // While exporting, the client tick rate itself follows the export clock (see TimerMixin), so
      // playback ticks here in both cases - VideoExporter just adds its own per-tick bookkeeping.
      if (VideoExporter.active) {
        VideoExporter.onClientTick()
      } else if (PlaybackManager.active && PlaybackControls.consumeTick()) {
        PlaybackManager.tick()
      }
    }

    // Overlay first - ModHud hides its panels while the overlay is showing, keeping only the bar.
    HudRenderCallback.EVENT.register { guiGraphics, _ -> ReplayScreenOverlay.render(guiGraphics) }
    HudRenderCallback.EVENT.register { guiGraphics, _ -> ModHud.render(guiGraphics) }

    // A small icon button beside the top button of the title/pause screen, in the same style (and,
    // on the title screen, the same column) as vanilla's language button.
    ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
      val anchorKey = when (screen) {
        is TitleScreen -> "menu.singleplayer"
        is PauseScreen -> "menu.returnToGame"
        else -> return@register
      }
      val buttons = Screens.getButtons(screen)
      val anchor = buttons.firstOrNull { (it.message.contents as? TranslatableContents)?.key == anchorKey }
      val x = anchor?.let { it.x - 24 } ?: 4
      val y = anchor?.y ?: 4
      buttons.add(RecordIconButton(x, y) {
        Minecraft.getInstance().setScreen(RecordingModMenu.open(MenuTab.RECORDINGS, screen))
      })
    }

    LOGGER.info("Recording Mod initialized")
  }

  // Keys that work while playing normally (not during playback) - see KeyboardHandlerMixin.
  fun onLiveKeyPress(key: Int, action: Int) {
    val mc = Minecraft.getInstance()
    if (action != GLFW.GLFW_PRESS || mc.screen != null || PlaybackManager.active) return
    if (key == GLFW.GLFW_KEY_L) markMoment()
  }

  // Saves instantly with an elapsed-time default name (e.g. "1:23") instead of opening a screen to
  // type one - typing a name would grab keyboard/mouse focus and interrupt whatever you're
  // actually doing at the moment you wanted to mark. Rename it afterward from the Markers tab.
  private fun markMoment() {
    val mc = Minecraft.getInstance()
    val file = RecordingManager.currentFile
    if (!RecordingManager.active || file == null) {
      mc.player?.displayClientMessage(Component.literal("Not recording - nothing to mark"), true)
      return
    }

    val tick = RecordingManager.currentTick
    val name = PlaybackControls.formatTime(tick)
    MarkerManager.save(name, file.nameWithoutExtension, tick)
    mc.player?.displayClientMessage(Component.literal("Marked moment ($name)"), true)
  }
}
