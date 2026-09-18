package me.aris.recordingmod

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue

// Replaces the legacy mod's native encoder (its source isn't in this repo, and it depended on
// compute shaders bound to Minecraft 1.12.2's internal framebuffer) with a much simpler pipeline:
// pipe raw RGBA frames straight to an ffmpeg subprocess over stdin.
//
// Playback still advances at its natural (real-time) pace - PlaybackManager.tick() is driven by
// the normal client tick loop either way - but rendering is uncapped (fps limit removed, vsync
// off) for the duration of an export, so real frames arrive faster than the target output fps.
// That's also what makes RecordingConfig.blendFactor mean anything: each output frame's pixels
// are the average of every real frame captured while playback was "inside" that output frame's
// time window (capped at blendFactor samples), which is genuine motion blur since those are
// actually distinct rendered moments, not the same frame copied onto itself.
//
// Frame capture uses double-buffered PBOs (see onFrameReady) instead of a plain synchronous
// glReadPixels - measured directly on this project, a synchronous read capped real fps to ~60-90
// even with vsync/the fps limit removed (vs. 300+ with the read skipped entirely), because
// glReadPixels-into-client-memory forces the CPU to block until all pending GPU work finishes.
// Reading back a PBO whose copy was queued a frame ago avoids that stall.
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
  private var blendFactor = 1
  private var startTimeNanos = 0L
  private var framesWritten = 0L
  private var rowBytes: ByteArray? = null
  private var flushBytes: ByteArray? = null
  private var accumBuffer: IntArray? = null
  private var outputFile: File? = null

  // Double-buffered pixel-pack buffer objects for asynchronous glReadPixels - see uncapFramerate's
  // comment for why the naive synchronous version defeated the whole point of uncapping fps. Each
  // frame we kick off a GPU-side copy into whichever PBO isn't currently "in flight", and read back
  // the *other* one - whose copy was queued a frame ago and is essentially certain to be done by
  // now - instead of blocking on this frame's own copy. That's one frame of latency, imperceptible
  // for a video capture. pboFramesQueued tracks how many reads have been queued so far, since there
  // is nothing valid to read back until the second call.
  private var pbos: IntArray? = null
  private var pboWriteIndex = 0
  private var pboFramesQueued = 0

  // Writing each frame to ffmpeg's stdin also happens on a background thread now, for the same
  // reason capture moved off the render thread to PBOs: if ffmpeg can't encode fast enough (a
  // heavy CRF like ours is real CPU work), its stdin pipe buffer fills up and a synchronous
  // write() blocks until it drains - stalling rendering just as badly as the old glReadPixels did,
  // just from the opposite end of the pipeline. The render thread only ever enqueues a frame
  // (dropping it instead of blocking if the writer has fallen far behind - see enqueueFrame); the
  // writer thread is the only thing that ever touches `stdin` directly.
  private var frameQueue: ArrayBlockingQueue<ByteArray>? = null
  private var writerThread: Thread? = null
  private val STOP_SENTINEL = ByteArray(0)
  private const val QUEUE_CAPACITY = 60

  // A pool of reusable ~width*height*4-byte buffers, recycled between the render thread (borrows
  // one, fills it, hands it to the writer) and the writer thread (returns it once written).
  // Without this, every enqueued frame would need a fresh allocation - at a few hundred frames a
  // second, a ~4MB frame means well over a gigabyte/sec of garbage, which is exactly the kind of
  // thing that causes periodic GC pauses (a very plausible explanation for the fps oscillating
  // between two bands instead of settling near the PBO fix's ceiling).
  private var freePool: ArrayBlockingQueue<ByteArray>? = null

  // Which output frame's worth of real frames we're currently accumulating into accumBuffer, and
  // how many samples have gone into it so far (see onFrameReady/flushAccumulator). -1 means
  // nothing captured yet.
  private var currentOutputFrameIndex = -1L
  private var accumSamples = 0

  // Restored once export finishes - see uncapFramerate/restoreFramerate.
  private var originalFramerateLimit = 0
  private var originalVsync = true

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
    blendFactor = RecordingConfig.blendFactor.coerceAtLeast(1)

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
    val procStdin = proc.outputStream
    stdin = procStdin
    this.outputFile = outputFile
    val frameSize = width * height * 4
    rowBytes = ByteArray(frameSize)
    flushBytes = ByteArray(frameSize)
    accumBuffer = IntArray(frameSize)

    val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    frameQueue = queue
    val pool = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    repeat(QUEUE_CAPACITY) { pool.put(ByteArray(frameSize)) }
    freePool = pool
    val writer = Thread({
      try {
        while (true) {
          val frame = queue.take()
          if (frame === STOP_SENTINEL) break
          procStdin.write(frame)
          pool.offer(frame)
        }
      } catch (e: Exception) {
        LOGGER.warn("Error writing frames to ffmpeg", e)
      }
    }, "recordingmod-video-writer")
    writer.isDaemon = true
    writer.start()
    writerThread = writer

    val pboIds = IntArray(2)
    GL15.glGenBuffers(pboIds)
    for (id in pboIds) {
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id)
      GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, frameSize.toLong(), GL15.GL_STREAM_READ)
    }
    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    pbos = pboIds
    pboWriteIndex = 0
    pboFramesQueued = 0

    currentOutputFrameIndex = -1L
    accumSamples = 0
    framesWritten = 0
    startTimeNanos = System.nanoTime()
    lastFrameNanos = startTimeNanos
    virtualElapsedSeconds = 0.0
    this.sloMoRegions = sloMoRegions

    uncapFramerate(mc)

    if (startTick != null && endTick != null) {
      PlaybackManager.startRange(recordingFile, startTick, endTick)
    } else {
      PlaybackManager.start(recordingFile)
    }
    active = true
    mc.player?.displayClientMessage(Component.literal("Exporting to $outputFile ..."), false)
    return true
  }

  // Rendering is normally capped to the display's refresh rate (vsync) or a configured fps limit
  // - during export we want real frames to arrive as fast as the GPU can produce them, both so
  // blendFactor has multiple genuinely distinct sub-frames to average per output frame, and so
  // there's more than one real frame per tick at all on a slow-moving camera. Playback's own tick
  // rate is untouched - this only affects how often we get a *new* rendered frame to sample.
  private fun uncapFramerate(mc: Minecraft) {
    originalFramerateLimit = mc.window.framerateLimit
    originalVsync = mc.options.enableVsync().get()
    mc.window.framerateLimit = 260
    mc.window.updateVsync(false)
  }

  private fun restoreFramerate(mc: Minecraft) {
    mc.window.framerateLimit = originalFramerateLimit
    mc.window.updateVsync(originalVsync)
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

    // Figure out which output frame's time window we're currently inside. Advancing our own
    // virtual clock (instead of reading real elapsed time directly) is what lets a slow-motion
    // region stretch itself over more output frames - see the field comment.
    val now = System.nanoTime()
    val realDeltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0
    lastFrameNanos = now
    val multiplier = sloMoRegions.firstOrNull { PlaybackManager.currentTick in it.range }?.slowMultiplier ?: 1
    virtualElapsedSeconds += realDeltaSeconds * multiplier
    val outputFrameIndex = (virtualElapsedSeconds * fps).toLong()

    val bytes = rowBytes ?: return
    val queue = frameQueue ?: return
    val pboIds = pbos ?: return

    // Kick off an async GPU-side copy of *this* frame into the PBO that isn't currently in
    // flight (the 0L "offset" means "into the bound buffer", not client memory, so this doesn't
    // block), then read back whichever PBO's copy was queued last call - which is what makes this
    // asynchronous: we're never waiting on the copy we just started, only one that's had a full
    // frame to finish in the background.
    val writeId = pboIds[pboWriteIndex]
    val readIndex = 1 - pboWriteIndex
    val readId = pboIds[readIndex]

    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, writeId)
    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)

    if (pboFramesQueued >= 1) {
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readId)
      val mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY)
      if (mapped != null) {
        mapped.get(bytes)
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)
      }
    }
    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)

    pboWriteIndex = readIndex
    if (pboFramesQueued < 2) pboFramesQueued++
    // Nothing valid to read back yet on the very first call - just let the pipeline fill up.
    if (pboFramesQueued < 2) return

    if (currentOutputFrameIndex == -1L) currentOutputFrameIndex = outputFrameIndex

    if (outputFrameIndex != currentOutputFrameIndex) {
      // We've moved into a new output frame's window - emit whatever was accumulated for the
      // one we just left (its average, i.e. the actual motion-blur result), then fill in any
      // output frames that got skipped entirely (real fps far below target) by duplicating the
      // latest raw frame, since there's no blended data for a window we never sampled at all.
      flushAccumulator(queue)
      var idx = currentOutputFrameIndex + 1
      while (idx < outputFrameIndex) {
        enqueueFrame(queue, bytes)
        idx++
      }
      currentOutputFrameIndex = outputFrameIndex
      accumSamples = 0
    }

    if (accumSamples < blendFactor) {
      val accum = accumBuffer
      if (accum != null) {
        for (i in bytes.indices) {
          accum[i] += bytes[i].toInt() and 0xFF
        }
        accumSamples++
      }
    }
  }

  // Hands a *copy* of the frame off to the writer thread (see frameQueue's own comment) - a copy
  // because rowBytes/flushBytes get reused and overwritten on the very next call. The copy is
  // borrowed from freePool rather than freshly allocated (see its own comment for why), falling
  // back to a real allocation only if the pool's ever run dry. Drops the frame if the writer has
  // fallen far enough behind to fill the queue, rather than blocking the render thread waiting for
  // room - a dropped/duplicated frame here and there is far less noticeable than the render thread
  // stalling on backpressure from ffmpeg's own encoding speed.
  private fun enqueueFrame(queue: ArrayBlockingQueue<ByteArray>, bytes: ByteArray) {
    val buf = freePool?.poll() ?: ByteArray(bytes.size)
    System.arraycopy(bytes, 0, buf, 0, bytes.size)
    if (!queue.offer(buf)) {
      LOGGER.warn("Frame queue full (ffmpeg falling behind) - dropping a frame")
      freePool?.offer(buf)
    }
    framesWritten++
  }

  // Averages whatever's been accumulated for the output frame we're about to leave and enqueues
  // it - the "blend" in blend factor. A no-op if nothing was ever sampled for it (shouldn't
  // normally happen, but harmless if it does).
  private fun flushAccumulator(queue: ArrayBlockingQueue<ByteArray>) {
    if (accumSamples == 0) return
    val accum = accumBuffer ?: return
    val flushed = flushBytes ?: return
    for (i in flushed.indices) {
      flushed[i] = (accum[i] / accumSamples).toByte()
      accum[i] = 0
    }
    enqueueFrame(queue, flushed)
  }

  fun stop() {
    if (!active) return
    active = false
    if (PlaybackManager.active) PlaybackManager.stop()

    frameQueue?.let { flushAccumulator(it) }
    currentOutputFrameIndex = -1L
    accumSamples = 0

    restoreFramerate(Minecraft.getInstance())

    val finishedFile = outputFile
    val finishingProcess = process
    val finishingStdin = stdin
    val finishingQueue = frameQueue
    val finishingWriter = writerThread

    // Finalizing the mp4 (ffmpeg flushing/closing the file) can take a moment - do it off the
    // render thread so we don't freeze a frame while waiting for it. The writer thread must drain
    // whatever's still queued and exit *before* we close stdin, or its last write(s) would race a
    // closed stream.
    Thread {
      try {
        finishingQueue?.put(STOP_SENTINEL)
        finishingWriter?.join()
        finishingStdin?.close()
        finishingProcess?.waitFor()
        LOGGER.info("Finished exporting to {}", finishedFile)
      } catch (e: Exception) {
        LOGGER.warn("Error finishing ffmpeg export", e)
      }
    }.start()

    pbos?.let { GL15.glDeleteBuffers(it) }
    pbos = null
    pboWriteIndex = 0
    pboFramesQueued = 0
    frameQueue = null
    writerThread = null
    freePool = null

    process = null
    stdin = null
    rowBytes = null
    flushBytes = null
    accumBuffer = null
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
