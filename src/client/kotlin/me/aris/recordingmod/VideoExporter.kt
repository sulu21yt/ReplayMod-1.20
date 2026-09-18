package me.aris.recordingmod

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

// Replaces the legacy mod's native encoder (its source isn't in this repo, and it depended on
// compute shaders bound to Minecraft 1.12.2's internal framebuffer) with a much simpler pipeline:
// pipe raw RGBA frames straight to an ffmpeg subprocess over stdin.
//
// This first version deliberately skips one thing the legacy renderer did: it runs in real time
// (playback and capture both proceed at their natural pace, so exporting a 10-minute recording
// takes about 10 real minutes) rather than decoupling from the display's real refresh rate to
// render as fast as possible. Worth revisiting, but a correct real-time capture was the priority
// for a first working export.
object VideoExporter {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/export")

  @Volatile
  var active = false
    private set

  private var process: Process? = null
  private var stdin: OutputStream? = null
  private var width = 0
  private var height = 0
  private var fps = 0
  private var startTimeNanos = 0L
  private var framesWritten = 0L
  private var pixelBuffer: ByteBuffer? = null
  private var rowBytes: ByteArray? = null
  private var outputFile: File? = null

  // The window size to restore once export finishes (only set if we actually resized it for
  // RecordingConfig.renderingWidth/Height / proxyRenderingWidth/Height - see beginResize).
  private var originalWindowWidth = 0
  private var originalWindowHeight = 0
  private var didResizeWindow = false

  // Set while waiting for a requested window resize to actually take effect (GLFW only applies it
  // once the engine's own glfwPollEvents() runs, typically the next frame or two) before the real
  // start can happen - see beginResize/onFrameReady. Frame-counted, not time-counted, since a very
  // low real fps during the wait would otherwise make a short deadline expire too easily.
  private data class PendingStart(
    val recordingFile: File,
    val outputFile: File,
    val startTick: Int?,
    val endTick: Int?,
    val sloMoRegions: List<BlueprintManager.SloMoRegion>,
    val targetWidth: Int,
    val targetHeight: Int,
    var lastSeenWidth: Int = -1,
    var lastSeenHeight: Int = -1,
    var stableFrames: Int = 0,
    var framesWaited: Int = 0
  )
  private var pendingStart: PendingStart? = null
  private const val RESIZE_TIMEOUT_FRAMES = 120
  // How many consecutive frames the window size must stay unchanged before we treat a resize as
  // "done" - even when it never reaches the exact target. A normal decorated window on a 1080p
  // display, for example, can never have exactly 1080px of *content* height and still fit on
  // screen (window chrome always eats a few pixels), so demanding a pixel-perfect match would give
  // up on every single export on such a display. Settling for whatever size the OS actually landed
  // on is far more useful than failing outright.
  private const val STABLE_FRAMES_REQUIRED = 5

  // Slow-motion support (blueprint rendering only, see startBlueprintRender): instead of an
  // absolute "elapsedSeconds = now - startTime" like the plain export used, we accumulate our own
  // virtual clock frame-by-frame so it can be made to run slower than real time while the current
  // tick falls inside a slow-motion region - which stretches that portion of the recording over
  // more output frames, i.e. exactly what slow motion is. With no regions (the plain export path)
  // this is numerically identical to the old absolute-time calculation.
  private var sloMoRegions: List<BlueprintManager.SloMoRegion> = emptyList()
  private var virtualElapsedSeconds = 0.0
  private var lastFrameNanos = 0L

  // True while either an actual export is running, or we're waiting for a requested window resize
  // to take effect before one can start (see beginResize) - callers that need to know "is anything
  // happening right now" (e.g. BlueprintRenderer sequencing one render after another) must check
  // this rather than `active` alone, since `active` is briefly false during that resize wait too.
  val isBusy: Boolean get() = active || pendingStart != null

  // Returns true if the export actually started (or was successfully queued behind a window
  // resize - see beginResize). Callers (e.g. RecordingsScreen) must check this before closing
  // themselves - if ffmpeg fails to launch, PlaybackManager.start() never runs, and closing the
  // screen anyway leaves the player staring at a blank world with no screen at all.
  fun start(recordingFile: File, outputFile: File): Boolean =
    requestStart(recordingFile, outputFile, startTick = null, endTick = null, sloMoRegions = emptyList(), proxy = false)

  // Renders just a blueprint's tick range (optionally with its slow-motion regions applied) to
  // either the proxy or final-quality output folder - see BlueprintRenderer for how a whole batch
  // of these gets driven one after another.
  fun startBlueprintRender(blueprint: BlueprintManager.Blueprint, proxy: Boolean): Boolean {
    val recordingFile = File(RecordingConfig.recordingsDir, "${blueprint.recordingBaseName}.rec")
    if (!recordingFile.exists()) {
      LOGGER.warn("Recording {} for blueprint {} no longer exists", recordingFile, blueprint.file.name)
      return false
    }
    val outDir = if (proxy) File("proxies") else File(RecordingConfig.finalRenderPath)
    val outputFile = File(outDir, "${blueprint.baseName}.mp4")
    return requestStart(
      recordingFile, outputFile,
      startTick = blueprint.startTick, endTick = blueprint.endTick, sloMoRegions = blueprint.sloMoRegions,
      proxy = proxy
    )
  }

  private fun requestStart(
    recordingFile: File,
    outputFile: File,
    startTick: Int?,
    endTick: Int?,
    sloMoRegions: List<BlueprintManager.SloMoRegion>,
    proxy: Boolean
  ): Boolean {
    if (isBusy) return false
    val mc = Minecraft.getInstance()

    val targetWidth = (if (proxy) RecordingConfig.proxyRenderingWidth else RecordingConfig.renderingWidth) and 1.inv()
    val targetHeight = (if (proxy) RecordingConfig.proxyRenderingHeight else RecordingConfig.renderingHeight) and 1.inv()

    // A fullscreen window's size is controlled by the display mode, not glfwSetWindowSize - and if
    // we're already at the target size there's nothing to wait for either. Either way, just start
    // immediately at whatever the window's current size actually is.
    if (mc.window.isFullscreen || (mc.window.width == targetWidth && mc.window.height == targetHeight)) {
      return startInternal(recordingFile, outputFile, startTick, endTick, sloMoRegions)
    }

    originalWindowWidth = mc.window.width
    originalWindowHeight = mc.window.height
    didResizeWindow = true
    GLFW.glfwSetWindowSize(mc.window.window, targetWidth, targetHeight)
    pendingStart = PendingStart(recordingFile, outputFile, startTick, endTick, sloMoRegions, targetWidth, targetHeight)
    return true
  }

  private fun startInternal(
    recordingFile: File,
    outputFile: File,
    startTick: Int?,
    endTick: Int?,
    sloMoRegions: List<BlueprintManager.SloMoRegion>
  ): Boolean {
    if (active) return false
    val mc = Minecraft.getInstance()

    // libx264 refuses odd dimensions ("height not divisible by 2") - the window's actual content
    // area isn't guaranteed to be even, so crop 1px off if needed rather than fail outright.
    width = mc.window.width and 1.inv()
    height = mc.window.height and 1.inv()
    fps = RecordingConfig.renderingFps.coerceIn(1, 240)

    outputFile.parentFile?.mkdirs()
    val command = listOf(
      RecordingConfig.ffmpegPath, "-y",
      "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "${width}x$height",
      "-framerate", fps.toString(), "-i", "-",
      "-vf", "vflip", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-crf", "12",
      outputFile.absolutePath
    )

    val proc = try {
      ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.appendTo(File(outputFile.parentFile, "ffmpeg.log")))
        .start()
    } catch (e: Exception) {
      LOGGER.warn("Failed to start ffmpeg ({})", RecordingConfig.ffmpegPath, e)
      // A toast (not a chat message) since this can be triggered with no player/world loaded at
      // all (e.g. from the title screen), where displayClientMessage would silently do nothing.
      SystemToast.add(
        mc.toasts,
        SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
        Component.literal("Failed to start ffmpeg"),
        Component.literal("Check the Ffmpeg Path setting")
      )
      return false
    }

    process = proc
    stdin = proc.outputStream
    this.outputFile = outputFile
    pixelBuffer = BufferUtils.createByteBuffer(width * height * 4)
    rowBytes = ByteArray(width * height * 4)
    framesWritten = 0
    startTimeNanos = System.nanoTime()
    lastFrameNanos = startTimeNanos
    virtualElapsedSeconds = 0.0
    this.sloMoRegions = sloMoRegions

    if (startTick != null && endTick != null) {
      PlaybackManager.startRange(recordingFile, startTick, endTick)
    } else {
      PlaybackManager.start(recordingFile)
    }
    active = true
    mc.player?.displayClientMessage(Component.literal("Exporting to $outputFile ..."), false)
    return true
  }

  // Called from WindowMixin's hook on Window.updateDisplay() - once per real rendered frame,
  // right before the buffer swap, so the just-drawn frame is still in the default framebuffer.
  fun onFrameReady() {
    pendingStart?.let { pending ->
      val mc = Minecraft.getInstance()
      val w = mc.window.width
      val h = mc.window.height

      if (w == pending.lastSeenWidth && h == pending.lastSeenHeight) {
        pending.stableFrames++
      } else {
        pending.lastSeenWidth = w
        pending.lastSeenHeight = h
        pending.stableFrames = 0
      }
      pending.framesWaited++

      val exactMatch = w == pending.targetWidth && h == pending.targetHeight
      val settled = pending.stableFrames >= STABLE_FRAMES_REQUIRED
      val timedOut = pending.framesWaited > RESIZE_TIMEOUT_FRAMES

      if (exactMatch || settled || timedOut) {
        if (!exactMatch) {
          LOGGER.info(
            "Window settled at {}x{} instead of the requested {}x{} (likely window chrome/screen " +
              "bounds) - using that size instead",
            w, h, pending.targetWidth, pending.targetHeight
          )
        }
        pendingStart = null
        if (!startInternal(pending.recordingFile, pending.outputFile, pending.startTick, pending.endTick, pending.sloMoRegions)) {
          restoreWindowSizeIfNeeded()
        }
      }
      return
    }

    if (!active) return

    if (!PlaybackManager.active) {
      stop()
      return
    }

    // Real frames may render faster or slower than the target output fps - figure out which
    // output frame index we should be at by now, and only capture (or duplicate) up to that.
    // Advancing our own virtual clock (instead of reading real elapsed time directly) is what
    // lets a slow-motion region stretch itself over more output frames - see the field comment.
    val now = System.nanoTime()
    val realDeltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0
    lastFrameNanos = now
    val multiplier = sloMoRegions.firstOrNull { PlaybackManager.currentTick in it.range }?.slowMultiplier ?: 1
    virtualElapsedSeconds += realDeltaSeconds * multiplier
    val targetFrameIndex = (virtualElapsedSeconds * fps).toLong()
    if (targetFrameIndex < framesWritten) return

    val buffer = pixelBuffer ?: return
    val bytes = rowBytes ?: return
    val out = stdin ?: return

    buffer.clear()
    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
    buffer.rewind()
    buffer.get(bytes)

    try {
      while (framesWritten <= targetFrameIndex) {
        out.write(bytes)
        framesWritten++
      }
    } catch (e: Exception) {
      LOGGER.warn("Failed writing frame to ffmpeg, stopping export", e)
      stop()
    }
  }

  fun stop() {
    if (!active) return
    active = false
    if (PlaybackManager.active) PlaybackManager.stop()

    val finishedFile = outputFile
    val finishingProcess = process
    val finishingStdin = stdin

    // Finalizing the mp4 (ffmpeg flushing/closing the file) can take a moment - do it off the
    // render thread so we don't freeze a frame while waiting for it.
    Thread {
      try {
        finishingStdin?.close()
        finishingProcess?.waitFor()
        LOGGER.info("Finished exporting to {}", finishedFile)
      } catch (e: Exception) {
        LOGGER.warn("Error finishing ffmpeg export", e)
      }
    }.start()

    process = null
    stdin = null
    pixelBuffer = null
    rowBytes = null
    outputFile = null
    sloMoRegions = emptyList()
    virtualElapsedSeconds = 0.0

    restoreWindowSizeIfNeeded()
  }

  private fun restoreWindowSizeIfNeeded() {
    if (!didResizeWindow) return
    didResizeWindow = false
    GLFW.glfwSetWindowSize(Minecraft.getInstance().window.window, originalWindowWidth, originalWindowHeight)
  }
}
