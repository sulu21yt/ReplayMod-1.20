package me.aris.recordingmod

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
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
      // While exporting, VideoExporter drives PlaybackManager.tick() itself directly (once per
      // captured frame, with no real-time pacing) so the export runs as fast as possible instead
      // of waiting on Minecraft's own real 20 ticks/sec - ticking here too would double-tick.
      if (PlaybackManager.active && !VideoExporter.active && PlaybackControls.consumeTick()) {
        PlaybackManager.tick()
      }
    }

    // Overlay first - ModHud hides its panels while the overlay is showing, keeping only the bar.
    HudRenderCallback.EVENT.register { guiGraphics, _ -> ReplayScreenOverlay.render(guiGraphics) }
    HudRenderCallback.EVENT.register { guiGraphics, _ -> ModHud.render(guiGraphics) }

    ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
      if (screen is TitleScreen || screen is PauseScreen) {
        Screens.getButtons(screen).add(
          Button.builder(Component.literal("Recording Mod")) {
            Minecraft.getInstance().setScreen(RecordingModMenu.open(MenuTab.RECORDINGS, screen))
          }.bounds(4, 4, 90, 20).build()
        )
      }
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
    ModHud.flashMarker()
    mc.player?.displayClientMessage(Component.literal("Marked moment ($name)"), true)
  }
}
