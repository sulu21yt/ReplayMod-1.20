package me.aris.recordingmod

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

// Fixed (not rebindable, not listed in Controls) keys while watching a replay - same layout as the
// legacy 1.12.2 mod's checkKeybinds(). Keys are read straight from KeyboardHandler.keyPress (see
// KeyboardHandlerMixin) instead of polled once per tick, since hold-K slow motion slows the tick
// rate itself down to 5/sec and a quick tap would otherwise be missed between ticks.
object PlaybackControls {
  private const val TICKS_PER_SECOND = 20
  private const val NORMAL_MS_PER_TICK = 1000f / 20f
  private const val SLOW_MS_PER_TICK = 1000f / 5f

  var paused = false
    private set

  // Ticks still allowed to run while paused - set by the one-frame-forward key. The game is only
  // actually frozen (see shouldFreezeGame) once these are used up.
  private var stepTicks = 0

  // World-only ticks (entities/animations run, the replay itself doesn't advance) granted after a
  // skip while paused - entities only move to the positions a seek just gave them by lerping over
  // their own next few ticks, which a frozen game would never run.
  private var settleTicks = 0

  var slowMotion = false
    private set

  // Start/end of the range the R key turns into a blueprint.
  var inTick: Int? = null
    private set
  var outTick: Int? = null
    private set

  var showHelp = true
    private set

  fun reset() {
    if (paused) Minecraft.getInstance().soundManager.resume()
    paused = false
    stepTicks = 0
    settleTicks = 0
    slowMotion = false
    inTick = null
    outTick = null
  }

  // No controls during an export - VideoExporter drives PlaybackManager.tick() itself and needs the
  // playback to progress in lockstep with the frames it captures.
  private fun canControl() = PlaybackManager.active && !VideoExporter.isBusy

  // Whether the normal once-per-client-tick PlaybackManager.tick() should run this tick.
  fun consumeTick(): Boolean {
    if (!paused) return true
    if (stepTicks > 0) {
      stepTicks--
      return true
    }
    if (settleTicks > 0) settleTicks--
    return false
  }

  // Read by MinecraftRunTickMixin - freezes the whole client (entity/particle/animation ticks and
  // the render partialTick) via vanilla's own pause flag, not just our packet feed, so a paused
  // replay is a true still frame instead of entities sliding to rest.
  fun shouldFreezeGame() = canControl() && paused && stepTicks == 0 && settleTicks == 0

  fun msPerTick(): Float {
    val mc = Minecraft.getInstance()
    slowMotion = canControl() &&
      (mc.screen == null || mc.screen is PlaybackTimelineScreen) &&
      InputConstants.isKeyDown(mc.window.window, GLFW.GLFW_KEY_K)
    return if (slowMotion) SLOW_MS_PER_TICK else NORMAL_MS_PER_TICK
  }

  // Returns true if the key was one of ours (and so shouldn't reach vanilla's own handling).
  fun handleKey(key: Int, action: Int): Boolean {
    if (!canControl()) return false
    val mc = Minecraft.getInstance()
    if (mc.screen != null && mc.screen !is PlaybackTimelineScreen) return false
    // Only the frame-step key repeats while held - every other one is a single action per press.
    if (action == GLFW.GLFW_REPEAT && key != GLFW.GLFW_KEY_PERIOD) return isOurKey(key)
    if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return isOurKey(key)

    when (key) {
      GLFW.GLFW_KEY_P -> returnToMenu()
      GLFW.GLFW_KEY_SPACE -> togglePause()
      GLFW.GLFW_KEY_D -> skipSeconds(5)
      GLFW.GLFW_KEY_A -> skipSeconds(-5)
      GLFW.GLFW_KEY_G -> skipSeconds(30)
      GLFW.GLFW_KEY_F -> skipSeconds(-30)
      GLFW.GLFW_KEY_X -> skipSeconds(10 * 60)
      GLFW.GLFW_KEY_Z -> skipSeconds(-10 * 60)
      GLFW.GLFW_KEY_PERIOD -> stepOneTick()
      GLFW.GLFW_KEY_I -> setIn()
      GLFW.GLFW_KEY_O -> setOut()
      GLFW.GLFW_KEY_R -> renderProxy()
      GLFW.GLFW_KEY_T -> toggleTimeline()
      GLFW.GLFW_KEY_H -> showHelp = !showHelp
      GLFW.GLFW_KEY_K -> {} // held, polled in msPerTick()
      else -> return false
    }
    return true
  }

  private fun isOurKey(key: Int) = key in setOf(
    GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_G,
    GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_I,
    GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_K
  )

  private fun returnToMenu() {
    val mc = Minecraft.getInstance()
    PlaybackManager.stop()
    mc.setScreen(RecordingModMenu.open(MenuTab.RECORDINGS, mc.screen))
  }

  private fun togglePause() {
    paused = !paused
    stepTicks = 0
    val sounds = Minecraft.getInstance().soundManager
    if (paused) sounds.pause() else sounds.resume()
  }

  private fun stepOneTick() {
    if (!paused) togglePause()
    stepTicks = 1
  }

  private fun skipSeconds(seconds: Int) = seekTo(PlaybackManager.currentTick + seconds * TICKS_PER_SECOND)

  fun seekTo(tick: Int) {
    PlaybackManager.seekTo(tick)
    if (paused) settleTicks = 4
  }

  private fun setIn() {
    inTick = PlaybackManager.currentTick
    outTick?.let { if (it <= PlaybackManager.currentTick) outTick = null }
    message("Render start set to ${formatTime(PlaybackManager.currentTick)}")
  }

  private fun setOut() {
    outTick = PlaybackManager.currentTick
    inTick?.let { if (it >= PlaybackManager.currentTick) inTick = null }
    message("Render end set to ${formatTime(PlaybackManager.currentTick)}")
  }

  private fun renderProxy() {
    val start = inTick
    val end = outTick
    val recording = PlaybackManager.currentFile
    if (start == null || end == null || recording == null) {
      message("Set a start (I) and an end (O) first")
      return
    }
    val blueprint = BlueprintManager.create(start, end, recording.nameWithoutExtension)
    if (blueprint == null) {
      message("Couldn't create a blueprint for that range")
      return
    }
    BlueprintRenderer.render(listOf(blueprint), proxy = true)
  }

  private fun toggleTimeline() {
    val mc = Minecraft.getInstance()
    mc.setScreen(if (mc.screen is PlaybackTimelineScreen) null else PlaybackTimelineScreen())
  }

  private fun message(text: String) {
    Minecraft.getInstance().player?.displayClientMessage(Component.literal(text), true)
  }

  fun formatTime(ticks: Int): String {
    val totalSeconds = ticks / TICKS_PER_SECOND
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
  }
}
