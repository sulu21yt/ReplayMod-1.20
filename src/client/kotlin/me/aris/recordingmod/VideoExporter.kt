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
// Playback is driven directly from here (see nextRenderFrameFraction/onFrameReady) instead of
// Minecraft's own real-time-paced client tick loop (which RecordingModClient explicitly skips
// while an export is active), with no real-time waiting at all, so the whole export runs as fast
// as rendering/encoding can sustain instead of waiting on real 20 ticks/sec. Each *output* frame
// still gets a distinct, correctly interpolated camera position between the two ticks it falls
// between (see nextRenderFrameFraction) rather than every frame just duplicating the latest raw
// tick's state - that's what keeps motion smooth despite ticks no longer being real-time-paced.
// This is a deliberate trade-off: RecordingConfig.blendFactor no longer has any real effect during
// export (there's only ever one genuinely distinct sample per output frame now - an interpolated
// position, not several real sub-frames to average - so there's nothing left to blend).
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
  private var framesWritten = 0L
  private var rowBytes: ByteArray? = null
  private var flushBytes: ByteArray? = null
  private var accumBuffer: IntArray? = null
  private var outputFile: File? = null

  // The video is encoded to a temp file first, and audio (see AudioExporter) captured to another
  // temp file alongside it - both get muxed into the real outputFile only once the export
  // finishes (see stop()). This sidesteps needing two *simultaneous* live streams into one ffmpeg
  // process (which would need a named pipe or similar): if audio capture didn't start (e.g. the
  // sound engine failed to switch to a loopback device), we just rename the video-only temp file
  // to outputFile instead - a graceful fallback to a silent export rather than failing outright.
  private var tempVideoFile: File? = null
  private var tempAudioFile: File? = null
  private var audioAvailable = false

  // Real game audio is captured one tick's worth at a time (see AudioExporter.pullSamples), at
  // its own natural un-sped-up pace, even during a slow-mo region where the *video* clock below
  // runs faster than that to stretch that portion over more output frames. Left alone, the muxed
  // audio track would end up shorter than its corresponding video segment and drift out of sync.
  // Fixed at mux time instead of in AudioExporter itself: track the audio-time (in ticks, i.e.
  // exactly how much real-paced audio AudioExporter has produced so far - a separate clock from
  // virtualElapsedSeconds below) span of each distinct multiplier the export passed through, then
  // stretch each span's *already-captured* audio with ffmpeg's atempo filter to match how much
  // longer the video got - see muxVideoAndAudio/atempoChain.
  private data class AudioSpeedSegment(val realStart: Double, val realEnd: Double, val multiplier: Int)
  private val audioSpeedSegments = mutableListOf<AudioSpeedSegment>()
  private var audioTicksElapsed = 0L
  private var segmentStartReal = 0.0
  private var segmentMultiplier = 1

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

  // The video's own clock: every captured/output frame is exactly 1/fps seconds of video, always
  // (see onFrameReady) - unaffected by slow-mo multipliers, since stretching now happens on the
  // *game-time* side instead (see gameSecondsElapsed) rather than by making some frames represent
  // more video-time than others.
  private var sloMoRegions: List<BlueprintManager.SloMoRegion> = emptyList()
  private var virtualElapsedSeconds = 0.0
  private const val TICK_SECONDS = 1.0 / 20.0

  // How much game time (in ticks, fractionally) *should* have elapsed by the current output frame
  // - advanced by (1/fps)/multiplier per output frame (see nextRenderFrameFraction), so a bigger
  // multiplier means game time advances more slowly relative to video time, i.e. stretches that
  // portion of the recording over more output frames - exactly what slow motion is. ticksConsumed
  // is how many PlaybackManager.tick() calls have actually been made so far to catch up to it; the
  // gap between the two (always in [0, 1)) is the interpolation fraction PlaybackManager's own
  // previous/current lerp uses for a smooth in-between camera position, instead of every output
  // frame just duplicating whatever the latest raw tick happened to be.
  private var gameSecondsElapsed = 0.0
  private var ticksConsumed = 0L
  private var lastRenderFraction = 1f

  // Guards against PlaybackManager.tick() being called reentrantly - some packet handlers (e.g.
  // login/respawn, via Minecraft.setLevel's loading-screen pump) call Minecraft.runTick()
  // themselves *while already inside* a tick() call made from here, which would otherwise re-enter
  // nextRenderFrameFraction/onFrameReady and read the next buffer record out of order mid-packet-
  // handling (confirmed via a real crash log: nested login/chunk packet handling with null
  // viewArea/player). This never came up with the old real-time-paced ticking (driven from a
  // single safe point in Minecraft's own outer loop, never reentered this way).
  private var tickInProgress = false

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
    val tempVideo = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.video.mp4")
    val tempAudio = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.audio.raw")
    val command = listOf(
      RecordingConfig.ffmpegPath, "-y",
      "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "${width}x$height",
      "-framerate", fps.toString(), "-i", "-",
      "-vf", "vflip", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-crf", "12",
      tempVideo.absolutePath
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
    this.tempVideoFile = tempVideo
    this.tempAudioFile = tempAudio
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
    virtualElapsedSeconds = 0.0
    gameSecondsElapsed = 0.0
    ticksConsumed = 0L
    lastRenderFraction = 1f
    this.sloMoRegions = sloMoRegions
    audioSpeedSegments.clear()
    audioTicksElapsed = 0L
    segmentStartReal = 0.0
    segmentMultiplier = 1

    uncapFramerate(mc)

    if (startTick != null && endTick != null) {
      PlaybackManager.startRange(recordingFile, startTick, endTick)
    } else {
      PlaybackManager.start(recordingFile)
    }

    // Not fatal if this fails - just export silent video, same as before audio capture existed.
    audioAvailable = AudioExporter.start(tempAudio)
    if (!audioAvailable) {
      LOGGER.warn("Audio capture unavailable - exporting without sound")
    }

    active = true
    mc.player?.displayClientMessage(Component.literal("Exporting to $outputFile ..."), false)
    return true
  }

  // Rendering is normally capped to the display's refresh rate (vsync) or a configured fps limit -
  // during export we want real frames to arrive as fast as the GPU can produce them, since each
  // captured frame now also drives one playback tick directly (see onFrameReady) - this is what
  // makes the whole export run as fast as possible instead of at real 20 ticks/sec.
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

    // Reentrancy guard: some packet handlers (e.g. login/respawn, via Minecraft.setLevel's
    // loading-screen pump) call Minecraft.runTick() themselves *while already inside* a tick()
    // call made from nextRenderFrameFraction (fired moments ago this same frame, via
    // GameRendererMixin, before this WindowMixin-hooked function runs), which re-enters this
    // function before the outer tick() has returned. Just skip capturing that inner pump frame
    // entirely - it's an internal loading-screen frame, not real gameplay we want in the export
    // anyway (confirmed via a real crash log otherwise: nested login/chunk packet handling with
    // null viewArea/player from reading the next buffer record out of order mid-packet-handling).
    if (tickInProgress) return

    // Ticking (and audio pulling, and the audio-speed-segment bookkeeping) already happened just
    // now via nextRenderFrameFraction, called from PlaybackManager.onRenderFrame earlier this same
    // frame - every output frame is simply exactly 1/fps seconds of video, always (see the field
    // comment on virtualElapsedSeconds).
    virtualElapsedSeconds += 1.0 / fps
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

  // Called from PlaybackManager.onRenderFrame (via GameRendererMixin), once per real frame and
  // *before* onFrameReady fires for that same frame (GameRenderer.render happens earlier in
  // Minecraft's loop than Window.updateDisplay). Decides how much game time this next output frame
  // should represent, ticks PlaybackManager forward to catch up (normally 0 or 1 calls - more only
  // if rendering briefly outpaces 20 ticks/sec by a wide margin), and returns the fractional
  // position between the last tick consumed and the next one, which PlaybackManager's own
  // previous/current lerp uses to render a smooth in-between camera position instead of every
  // output frame just duplicating whichever raw tick state happened to be current.
  fun nextRenderFrameFraction(): Float {
    if (!active) return 1f
    // Reentrant call from inside our own tick() below (see tickInProgress's comment) - the
    // fraction doesn't matter here since onFrameReady's own guard will skip capturing this inner
    // pump frame entirely anyway; just don't touch any of the counters.
    if (tickInProgress) return lastRenderFraction

    val multiplier = sloMoRegions.firstOrNull { PlaybackManager.currentTick in it.range }?.slowMultiplier ?: 1
    gameSecondsElapsed += (1.0 / fps) / multiplier
    val targetTicks = (gameSecondsElapsed * 20.0).toLong()

    if (targetTicks > ticksConsumed) {
      tickInProgress = true
      try {
        while (ticksConsumed < targetTicks && PlaybackManager.active) {
          PlaybackManager.tick()
          ticksConsumed++
          if (audioAvailable) {
            AudioExporter.pullSamples()
            audioTicksElapsed++
          }
          val tickMultiplier = sloMoRegions.firstOrNull { PlaybackManager.currentTick in it.range }?.slowMultiplier ?: 1
          if (audioAvailable && sloMoRegions.isNotEmpty() && tickMultiplier != segmentMultiplier) {
            val realElapsedSeconds = audioTicksElapsed * TICK_SECONDS
            audioSpeedSegments.add(AudioSpeedSegment(segmentStartReal, realElapsedSeconds, segmentMultiplier))
            segmentStartReal = realElapsedSeconds
            segmentMultiplier = tickMultiplier
          }
        }
      } finally {
        tickInProgress = false
      }
    }

    if (!PlaybackManager.active) return 1f

    val fraction = ((gameSecondsElapsed * 20.0) - ticksConsumed).toFloat().coerceIn(0f, 1f)
    lastRenderFraction = fraction
    return fraction
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
    if (audioAvailable && sloMoRegions.isNotEmpty()) {
      val realElapsedSeconds = audioTicksElapsed * TICK_SECONDS
      audioSpeedSegments.add(AudioSpeedSegment(segmentStartReal, realElapsedSeconds, segmentMultiplier))
    }
    AudioExporter.stop()

    frameQueue?.let { flushAccumulator(it) }
    currentOutputFrameIndex = -1L
    accumSamples = 0

    restoreFramerate(Minecraft.getInstance())

    val finishedFile = outputFile
    val finishingTempVideo = tempVideoFile
    val finishingTempAudio = tempAudioFile
    val finishingAudioAvailable = audioAvailable
    val finishingAudioSpeedSegments = audioSpeedSegments.toList()
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

        if (finishedFile != null && finishingTempVideo != null) {
          if (finishingAudioAvailable && finishingTempAudio != null && finishingTempAudio.exists()) {
            muxVideoAndAudio(finishingTempVideo, finishingTempAudio, finishedFile, finishingAudioSpeedSegments)
            finishingTempVideo.delete()
            finishingTempAudio.delete()
          } else if (!finishingTempVideo.renameTo(finishedFile)) {
            LOGGER.warn("Could not move {} to {}", finishingTempVideo, finishedFile)
          }
        }
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
    tempVideoFile = null
    tempAudioFile = null
    audioAvailable = false
    audioSpeedSegments.clear()
    segmentStartReal = 0.0
    segmentMultiplier = 1
    audioTicksElapsed = 0L
    sloMoRegions = emptyList()
    virtualElapsedSeconds = 0.0

    restoreWindowSizeIfNeeded()
  }

  // Muxes the finished silent video and raw captured audio into the real output file. Runs
  // synchronously on the same background "finishing" thread that already waits for the video
  // ffmpeg process, since it's off the render thread either way. If any segment ran at other than
  // 1x (a blueprint export with slow-mo regions - see AudioSpeedSegment's comment), each such span
  // of the real-time-captured audio is time-stretched with atempo to match how much longer its
  // corresponding video portion got, then all spans are concatenated back together; a plain export
  // (no slow-mo) always has an all-1x segment list and takes the simple stream-copy path as before.
  private fun muxVideoAndAudio(video: File, audio: File, output: File, segments: List<AudioSpeedSegment>) {
    val usable = segments.filter { it.realEnd > it.realStart }
    val needsFilter = usable.any { it.multiplier != 1 }

    val command = if (!needsFilter) {
      listOf(
        RecordingConfig.ffmpegPath, "-y",
        "-i", video.absolutePath,
        "-f", "s16le", "-ar", AudioExporter.SAMPLE_RATE.toString(), "-ac", AudioExporter.CHANNELS.toString(),
        "-i", audio.absolutePath,
        "-c:v", "copy", "-c:a", "aac", "-shortest",
        output.absolutePath
      )
    } else {
      val filterParts = mutableListOf<String>()
      val labels = mutableListOf<String>()
      usable.forEachIndexed { i, seg ->
        val label = "a$i"
        val tempo = if (seg.multiplier != 1) {
          "," + atempoChain(1.0 / seg.multiplier).joinToString(",") { "atempo=${fmtSeconds(it)}" }
        } else {
          ""
        }
        filterParts.add(
          "[1:a]atrim=start=${fmtSeconds(seg.realStart)}:end=${fmtSeconds(seg.realEnd)},asetpts=PTS-STARTPTS$tempo[$label]"
        )
        labels.add("[$label]")
      }
      filterParts.add("${labels.joinToString("")}concat=n=${labels.size}:v=0:a=1[aout]")

      listOf(
        RecordingConfig.ffmpegPath, "-y",
        "-i", video.absolutePath,
        "-f", "s16le", "-ar", AudioExporter.SAMPLE_RATE.toString(), "-ac", AudioExporter.CHANNELS.toString(),
        "-i", audio.absolutePath,
        "-filter_complex", filterParts.joinToString(";"),
        "-map", "0:v", "-map", "[aout]",
        "-c:v", "copy", "-c:a", "aac", "-shortest",
        output.absolutePath
      )
    }

    try {
      val proc = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.appendTo(File(output.parentFile, "ffmpeg.log")))
        .start()
      proc.waitFor()
    } catch (e: Exception) {
      LOGGER.warn("Failed to mux audio into {}", output, e)
    }
  }

  private fun fmtSeconds(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)

  // ffmpeg's atempo filter only accepts a factor in [0.5, 2.0] per node - chain multiple nodes to
  // reach a bigger stretch/squeeze than a single node allows (e.g. a 4x slow-mo region needs an
  // atempo factor of 0.25, split into two 0.5 nodes).
  private fun atempoChain(factor: Double): List<Double> {
    var remaining = factor
    val chain = mutableListOf<Double>()
    while (remaining < 0.5) {
      chain.add(0.5)
      remaining /= 0.5
    }
    while (remaining > 2.0) {
      chain.add(2.0)
      remaining /= 2.0
    }
    chain.add(remaining)
    return chain
  }

  private fun restoreWindowSizeIfNeeded() {
    if (!didResizeWindow) return
    didResizeWindow = false
    GLFW.glfwSetWindowSize(Minecraft.getInstance().window.window, originalWindowWidth, originalWindowHeight)
  }
}
