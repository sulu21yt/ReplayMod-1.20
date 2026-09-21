package me.aris.recordingmod

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object RecordingModClient : ClientModInitializer {
  val LOGGER = LoggerFactory.getLogger("recordingmod")

  private val toggleRecordingKey = KeyMapping(
    "key.recordingmod.toggle_recording",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val playLastRecordingKey = KeyMapping(
    "key.recordingmod.play_last_recording",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val leavePlaybackKey = KeyMapping(
    "key.recordingmod.leave_playback",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val openRecordingsKey = KeyMapping(
    "key.recordingmod.open_recordings",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val openSettingsKey = KeyMapping(
    "key.recordingmod.open_settings",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val markMomentKey = KeyMapping(
    "key.recordingmod.mark_moment",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val skipBack5Key = KeyMapping(
    "key.recordingmod.skip_back_5s",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val skipForward5Key = KeyMapping(
    "key.recordingmod.skip_forward_5s",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val skipBack30Key = KeyMapping(
    "key.recordingmod.skip_back_30s",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val skipForward30Key = KeyMapping(
    "key.recordingmod.skip_forward_30s",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private val openTimelineKey = KeyMapping(
    "key.recordingmod.open_timeline",
    InputConstants.Type.KEYSYM,
    InputConstants.UNKNOWN.value,
    "category.recordingmod"
  )

  private const val TICKS_PER_SECOND = 20

  override fun onInitializeClient() {
    RecordingConfig.load()

    KeyBindingHelper.registerKeyBinding(toggleRecordingKey)
    KeyBindingHelper.registerKeyBinding(playLastRecordingKey)
    KeyBindingHelper.registerKeyBinding(leavePlaybackKey)
    KeyBindingHelper.registerKeyBinding(openRecordingsKey)
    KeyBindingHelper.registerKeyBinding(openSettingsKey)
    KeyBindingHelper.registerKeyBinding(markMomentKey)
    KeyBindingHelper.registerKeyBinding(skipBack5Key)
    KeyBindingHelper.registerKeyBinding(skipForward5Key)
    KeyBindingHelper.registerKeyBinding(skipBack30Key)
    KeyBindingHelper.registerKeyBinding(skipForward30Key)
    KeyBindingHelper.registerKeyBinding(openTimelineKey)

    ClientTickEvents.END_CLIENT_TICK.register { mc ->
      while (toggleRecordingKey.consumeClick()) {
        toggleRecording()
      }
      while (playLastRecordingKey.consumeClick()) {
        playLastRecording()
      }
      while (leavePlaybackKey.consumeClick()) {
        if (PlaybackManager.active) {
          PlaybackManager.stop()
        }
      }
      while (openRecordingsKey.consumeClick()) {
        if (mc.screen == null) {
          mc.setScreen(RecordingsScreen(null))
        }
      }
      while (openSettingsKey.consumeClick()) {
        if (mc.screen == null) {
          mc.setScreen(RecordingSettingsScreen(null))
        }
      }
      while (markMomentKey.consumeClick()) {
        markMoment()
      }
      while (skipBack5Key.consumeClick()) {
        skipSeconds(-5)
      }
      while (skipForward5Key.consumeClick()) {
        skipSeconds(5)
      }
      while (skipBack30Key.consumeClick()) {
        skipSeconds(-30)
      }
      while (skipForward30Key.consumeClick()) {
        skipSeconds(30)
      }
      while (openTimelineKey.consumeClick()) {
        if (PlaybackManager.active && mc.screen == null) {
          mc.setScreen(PlaybackTimelineScreen())
        }
      }

      RecordingManager.onClientTick()
      // While exporting, VideoExporter drives PlaybackManager.tick() itself directly (once per
      // captured frame, with no real-time pacing) so the export runs as fast as possible instead
      // of waiting on Minecraft's own real 20 ticks/sec - ticking here too would double-tick.
      if (PlaybackManager.active && !VideoExporter.active) {
        PlaybackManager.tick()
      }
    }

    LOGGER.info("Recording Mod (1.20.1 rewrite, milestone 1) initialized")
  }

  // Scrubbing during normal playback - not during an export, since VideoExporter relies on
  // PlaybackManager's tick count progressing in lockstep with the frames it's capturing.
  private fun skipSeconds(seconds: Int) {
    if (!PlaybackManager.active || VideoExporter.active) return
    val targetTick = PlaybackManager.currentTick + seconds * TICKS_PER_SECOND
    PlaybackManager.seekTo(targetTick)
    if (PlaybackManager.active) {
      val mc = net.minecraft.client.Minecraft.getInstance()
      mc.player?.displayClientMessage(
        Component.literal("Skipped to ${PlaybackManager.currentTick / TICKS_PER_SECOND}s"), true
      )
    }
  }

  // Saves instantly with an elapsed-time default name (e.g. "1:23") instead of opening a screen to
  // type one - typing a name would grab keyboard/mouse focus and interrupt whatever you're
  // actually doing at the moment you wanted to mark. Rename it afterward from MarkersScreen instead.
  private fun markMoment() {
    val mc = net.minecraft.client.Minecraft.getInstance()

    val file = RecordingManager.currentFile
    if (!RecordingManager.active || file == null) {
      mc.player?.displayClientMessage(Component.literal("Not recording - nothing to mark"), false)
      return
    }

    val tick = RecordingManager.currentTick
    val totalSeconds = tick / TICKS_PER_SECOND
    val name = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    MarkerManager.save(name, file.nameWithoutExtension, tick)
    mc.player?.displayClientMessage(Component.literal("Marked moment ($name)"), true)
  }

  private fun toggleRecording() {
    val mc = net.minecraft.client.Minecraft.getInstance()
    if (RecordingManager.active) {
      RecordingManager.stop()
      mc.player?.displayClientMessage(Component.literal("Stopped recording"), false)
    } else {
      val name = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(Date())
      val file = File(RecordingConfig.recordingsDir, "$name.rec")
      RecordingManager.start(file)
      mc.player?.displayClientMessage(Component.literal("Started recording to $file"), false)
    }
  }

  private fun playLastRecording() {
    val mc = net.minecraft.client.Minecraft.getInstance()

    // Stop any active recording first - otherwise we'd read a file that's still being
    // written to, which can hand playback a snapshot that ends mid-record.
    if (RecordingManager.active) {
      RecordingManager.stop()
    }

    val latest = RecordingConfig.recordingsDir.listFiles { f -> f.extension == "rec" }
      ?.maxByOrNull { it.lastModified() }

    if (latest == null) {
      mc.player?.displayClientMessage(
        Component.literal("No recordings found in ${RecordingConfig.recordingsDir}"), false
      )
      return
    }

    PlaybackManager.start(latest)
    mc.player?.displayClientMessage(Component.literal("Playing back $latest"), false)
  }
}
