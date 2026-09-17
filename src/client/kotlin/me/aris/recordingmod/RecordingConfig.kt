package me.aris.recordingmod

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

// Persisted settings, replacing the legacy LiteLoader mod's @ExposableOptions config (that API
// doesn't exist on Fabric) - see RecordingSettingsScreen for the menu that edits these. The
// 7-Zip path from the legacy config is intentionally gone: recordings are no longer compressed,
// so there's nothing left to decompress. ffmpegPath is new - the legacy mod encoded video with
// its own native encoder (source not in this repo); this port pipes raw frames to ffmpeg instead.
object RecordingConfig {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/config")
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val file = FabricLoader.getInstance().configDir.resolve("recordingmod.json").toFile()

  var recordingPath = "recordings"
  var finalRenderPath = "final"
  var ffmpegPath = "ffmpeg"
  var renderingWidth = 1920
  var renderingHeight = 1080
  var renderingFps = 60
  var blendFactor = 10
  var proxyRenderingWidth = 1920
  var proxyRenderingHeight = 1080

  private data class Data(
    val recordingPath: String = "recordings",
    val finalRenderPath: String = "final",
    val ffmpegPath: String = "ffmpeg",
    val renderingWidth: Int = 1920,
    val renderingHeight: Int = 1080,
    val renderingFps: Int = 60,
    val blendFactor: Int = 10,
    val proxyRenderingWidth: Int = 1920,
    val proxyRenderingHeight: Int = 1080
  )

  fun load() {
    if (!file.exists()) return
    try {
      val data = gson.fromJson(file.readText(), Data::class.java) ?: return
      recordingPath = data.recordingPath
      finalRenderPath = data.finalRenderPath
      ffmpegPath = data.ffmpegPath
      renderingWidth = data.renderingWidth
      renderingHeight = data.renderingHeight
      renderingFps = data.renderingFps
      blendFactor = data.blendFactor
      proxyRenderingWidth = data.proxyRenderingWidth
      proxyRenderingHeight = data.proxyRenderingHeight
    } catch (e: Exception) {
      LOGGER.warn("Failed to load {}, using defaults", file, e)
    }
  }

  fun save() {
    val data = Data(
      recordingPath, finalRenderPath, ffmpegPath, renderingWidth, renderingHeight,
      renderingFps, blendFactor, proxyRenderingWidth, proxyRenderingHeight
    )
    try {
      file.parentFile?.mkdirs()
      file.writeText(gson.toJson(data))
    } catch (e: Exception) {
      LOGGER.warn("Failed to save {}", file, e)
    }
  }

  val recordingsDir: File get() = File(recordingPath)
}
