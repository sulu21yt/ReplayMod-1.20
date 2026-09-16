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

  private val recordingsDir = File("recordings")

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

  override fun onInitializeClient() {
    KeyBindingHelper.registerKeyBinding(toggleRecordingKey)
    KeyBindingHelper.registerKeyBinding(playLastRecordingKey)
    KeyBindingHelper.registerKeyBinding(leavePlaybackKey)
    KeyBindingHelper.registerKeyBinding(openRecordingsKey)

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

      RecordingManager.onClientTick()
      if (PlaybackManager.active) {
        PlaybackManager.tick()
      }
    }

    LOGGER.info("Recording Mod (1.20.1 rewrite, milestone 1) initialized")
  }

  private fun toggleRecording() {
    val mc = net.minecraft.client.Minecraft.getInstance()
    if (RecordingManager.active) {
      RecordingManager.stop()
      mc.player?.displayClientMessage(Component.literal("Stopped recording"), false)
    } else {
      val name = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(Date())
      val file = File(recordingsDir, "$name.rec")
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

    val latest = recordingsDir.listFiles { f -> f.extension == "rec" }
      ?.maxByOrNull { it.lastModified() }

    if (latest == null) {
      mc.player?.displayClientMessage(Component.literal("No recordings found in $recordingsDir"), false)
      return
    }

    PlaybackManager.start(latest)
    mc.player?.displayClientMessage(Component.literal("Playing back $latest"), false)
  }
}
