package me.aris.recordingmod

import net.minecraft.client.Minecraft
import org.lwjgl.BufferUtils
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.SOFTLoopback
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.IntBuffer
import java.nio.ShortBuffer

// Captures real game audio (Hypixel's music/sound effects included, not just a microphone - see
// the legacy mod's Recorder.kt, which only ever managed the latter) for VideoExporter, via OpenAL
// Soft's loopback device extension instead of any OS-level virtual audio cable - nothing for
// anyone running the mod to install, unlike VB-CABLE/Stereo Mix. Works by redirecting Minecraft's
// own sound engine (see LibraryMixin) to open a loopback device instead of a real one for the
// duration of capture, then manually pulling rendered samples on a schedule - a loopback device
// doesn't play automatically the way a real one does, nothing comes out unless something asks for
// it explicitly.
//
// This first version has no live monitoring: swapping the sound device means nothing plays
// through real speakers while capturing, only into our buffer. A worthwhile follow-up is echoing
// the captured samples back out through a normal line, but that's real added complexity and
// wasn't the priority for getting audio into the export at all.
//
// pullSamples() renders exactly one tick's worth of audio (SAMPLE_RATE / 20) each time it's
// called, rather than however much real wall-clock time has passed - VideoExporter now drives
// PlaybackManager.tick() directly, one tick per captured frame with no real-time pacing at all (so
// export runs as fast as rendering/encoding can sustain), and calls this exactly once per tick to
// match. The sound engine itself always renders normal-speed, un-sped-up audio content regardless
// of how fast we ask for it (alcRenderSamplesSOFT has no concept of wall-clock time, only of how
// many samples have been requested so far), so the captured file ends up representing the
// recording's own real-time audio timeline even though the export process itself runs much
// faster - VideoExporter separately stretches slow-motion spans of it back in sync at mux time
// (see its AudioSpeedSegment/atempo handling), since this capture is unaware of slow-mo entirely.
object AudioExporter {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/audio")

  const val SAMPLE_RATE = 48000
  const val CHANNELS = 2

  @Volatile
  var wantsLoopback = false
    private set

  @Volatile
  var active = false
    private set

  private const val SAMPLES_PER_TICK = SAMPLE_RATE / 20

  private var device = 0L
  private var outputStream: BufferedOutputStream? = null
  private var sampleBuffer: ShortBuffer? = null
  private var byteScratch: ByteArray? = null

  // Called by LibraryMixin's redirect right after it opens the loopback device - we learn the
  // handle this way rather than via reflection/accessors into Mojang's private Library fields.
  fun onDeviceOpened(device: Long) {
    this.device = device
  }

  fun loopbackContextAttribs(): IntBuffer {
    val attribs = BufferUtils.createIntBuffer(7)
    attribs.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
    attribs.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
    attribs.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE)
    attribs.put(0)
    attribs.flip()
    return attribs
  }

  // Starts capturing to `audioFile` as raw interleaved 16-bit little-endian PCM (no header - the
  // muxing ffmpeg command declares the format explicitly instead). Returns false if the sound
  // engine failed to switch over, in which case the caller should fall back to a video-only
  // export rather than failing it outright.
  fun start(audioFile: File): Boolean {
    wantsLoopback = true
    device = 0L
    try {
      Minecraft.getInstance().soundManager.reload()
    } catch (e: Exception) {
      LOGGER.warn("Failed to reload sound engine for audio capture", e)
      wantsLoopback = false
      return false
    }
    if (device == 0L) {
      LOGGER.warn("Sound engine reload did not open a loopback device")
      wantsLoopback = false
      return false
    }

    audioFile.parentFile?.mkdirs()
    outputStream = BufferedOutputStream(FileOutputStream(audioFile))
    sampleBuffer = BufferUtils.createShortBuffer(SAMPLES_PER_TICK * CHANNELS)
    byteScratch = ByteArray(SAMPLES_PER_TICK * CHANNELS * 2)
    active = true
    return true
  }

  // Called once per tick from VideoExporter.onFrameReady (right after PlaybackManager.tick()) -
  // renders exactly one tick's worth of normal-speed audio content, regardless of how much real
  // time that call actually took.
  fun pullSamples() {
    if (!active) return
    val dev = device
    if (dev == 0L) return

    val buffer = sampleBuffer ?: return
    val scratch = byteScratch ?: return
    val out = outputStream ?: return

    try {
      buffer.clear()
      SOFTLoopback.alcRenderSamplesSOFT(dev, buffer, SAMPLES_PER_TICK)
      buffer.rewind()
      val sampleCount = SAMPLES_PER_TICK * CHANNELS
      for (i in 0 until sampleCount) {
        val sample = buffer.get(i).toInt()
        scratch[i * 2] = (sample and 0xFF).toByte()
        scratch[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
      }
      out.write(scratch, 0, sampleCount * 2)
    } catch (e: Exception) {
      LOGGER.warn("Failed writing captured audio, stopping audio capture", e)
      stop()
    }
  }

  // Stops capturing and switches the sound engine back to a real device. Safe to call even if
  // capture never started (e.g. VideoExporter always calls this on export stop).
  fun stop() {
    if (!active) {
      if (wantsLoopback) restoreRealDevice()
      return
    }
    active = false

    try {
      outputStream?.flush()
      outputStream?.close()
    } catch (e: Exception) {
      LOGGER.warn("Error closing audio capture file", e)
    }
    outputStream = null
    sampleBuffer = null
    byteScratch = null
    device = 0L

    restoreRealDevice()
  }

  private fun restoreRealDevice() {
    wantsLoopback = false
    try {
      Minecraft.getInstance().soundManager.reload()
    } catch (e: Exception) {
      LOGGER.warn("Failed to restore sound engine after audio capture", e)
    }
  }
}
