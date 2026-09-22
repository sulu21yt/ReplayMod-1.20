package me.aris.recordingmod

import io.netty.buffer.Unpooled
import me.aris.recordingmod.RecordingFormat.BLOCK_BREAK_PROGRESS
import me.aris.recordingmod.RecordingFormat.BLOCK_CHANGE
import me.aris.recordingmod.RecordingFormat.LOCAL_LEVEL_EVENT
import me.aris.recordingmod.RecordingFormat.LOCAL_PLAY_SOUND
import me.aris.recordingmod.RecordingFormat.LOCAL_SKIN_CUSTOMIZATION
import me.aris.recordingmod.RecordingFormat.LOCAL_SNEAK_STATE
import me.aris.recordingmod.RecordingFormat.LOCAL_START_USING_ITEM
import me.aris.recordingmod.RecordingFormat.LOCAL_STOP_USING_ITEM
import me.aris.recordingmod.RecordingFormat.MINING_PARTICLE
import me.aris.recordingmod.RecordingFormat.PLAYER_SNAPSHOT
import me.aris.recordingmod.RecordingFormat.SWING
import me.aris.recordingmod.RecordingFormat.TICK_END
import me.aris.recordingmod.mixins.EntityMovementSoundInvokerMixin
import me.aris.recordingmod.mixins.LocalPlayerCrouchingAccessorMixin
import me.aris.recordingmod.mixins.PlayerModelCustomisationAccessorMixin
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs

// Plays a recording back by feeding the captured packets through a real (but disconnected)
// ClientPacketListener - the same class vanilla uses for a live server connection - so that world
// reconstruction (entities, chunks, chat, etc.) is handled by Mojang's own, always-up-to-date logic
// instead of a hand-copied reimplementation.
object PlaybackManager {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/playback")

  @Volatile
  var active = false
    private set

  private var buf: FriendlyByteBuf? = null
  private var listener: ClientPacketListenerHandle? = null

  // Remembered purely so seekTo can restart playback from tick 0 for a backward seek - there's no
  // keyframe format to jump within, so "rewind" really means "replay from the start again, fast".
  private var currentFile: File? = null

  private data class LookState(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

  private var previous: LookState? = null
  private var current: LookState? = null

  // Our own reimplementation of Entity's private moveDist/nextStep fields, since footstep sounds
  // are normally a side effect of Entity.move()'s physics pass, which playback never calls (see
  // applySnapshot). Mirrors the threshold logic in Entity.move() (moveDist accumulates horizontal
  // distance * 0.6, a step fires once it passes nextStep, which then advances to floor(moveDist)+1).
  private var stepMoveDist = 0f
  private var stepNextThreshold = 1f
  private var wasInWater = false

  // Smoothed per-tick displacement used only to derive a stable body-facing direction - see
  // updateBodyAndHeadRotation's comment for why a single tick's raw dx/dz isn't good enough on
  // its own.
  private var smoothedDx = 0.0
  private var smoothedDz = 0.0

  // How many ticks of this playback we've processed so far - used by startAtTick to know when
  // it's caught up to the requested marker tick.
  @Volatile
  var currentTick = 0
    private set

  // Length of the current recording, in ticks, for the playback timeline (see
  // PlaybackTimelineScreen) - null if there's no sidecar metadata for it (see RecordingMetadata),
  // e.g. a recording made before this feature existed, or an interrupted one that never stopped
  // cleanly. Callers just treat null as "unknown length" rather than falling back to a full scan.
  @Volatile
  var totalTicks: Int? = null
    private set

  // While fast-forwarding to a marker (see startAtTick), every tick between 0 and the target
  // flies by near-instantly - playing every sound that would normally happen along the way would
  // be a deafening burst of noise instead of a clean jump. Real sound packets and our own
  // movement/local-event sound triggers are muted for the duration; world/entity state (which is
  // what actually needs to be correct once we arrive) is applied normally either way.
  private var isFastForwarding = false

  // Set by startRange (blueprint rendering only) - once currentTick reaches this, tick() stops
  // playback on its own, the same way it already does on reaching end of file.
  private var stopAtTick: Int? = null

  // Guards against tick() being called reentrantly - some packet handlers (e.g. login/respawn, via
  // Minecraft.setLevel's loading-screen pump) call Minecraft.runTick() themselves *while already
  // inside* a tick() call, which can re-trigger another tick() before the outer one has returned
  // (confirmed via a real crash log: nested packet handling reading the next buffer record out of
  // order mid-packet-handling, eventually leaving `level` null for the rest of playback). This can
  // happen via either caller - VideoExporter driving tick() directly during a fast export, or the
  // normal real-time-paced ClientTickEvents.END_CLIENT_TICK call during ordinary playback - so the
  // guard lives here, at the single actual entry point, rather than being each caller's problem.
  private var tickInProgress = false

  fun start(file: File) {
    resetState()

    val mc = Minecraft.getInstance()
    // If we're currently in a real world (e.g. a real singleplayer session), properly leave it
    // first - exactly like a normal disconnect - instead of just overwriting mc.level/mc.player
    // out from under it. Otherwise mc.singleplayerServer keeps pointing at the REAL integrated
    // server for the whole replay, and later, when leaving/finishing the replay, the same
    // disconnect logic (see stop()) waits forever for that real server to shut down - since
    // nothing ever told it to - hanging the game on a black screen.
    leaveCurrentWorld(mc)

    val connection = Connection(PacketFlow.CLIENTBOUND)
    val packetListener = net.minecraft.client.multiplayer.ClientPacketListener(
      mc,
      null,
      connection,
      null,
      mc.user.gameProfile,
      mc.telemetryManager.createWorldSessionManager(false, null, null)
    )
    connection.setListener(packetListener)

    this.listener = ClientPacketListenerHandle(connection, packetListener)
    this.buf = FriendlyByteBuf(Unpooled.wrappedBuffer(file.readBytes()))
    this.currentFile = file
    this.totalTicks = RecordingMetadata.readTotalTicks(file)
    this.active = true
    LOGGER.info("Started playback of {}", file)
  }

  // Internal: just forgets our own playback state, without touching the game world. Used at the
  // start of a new playback, where we do NOT want to quit to the title screen.
  private fun resetState() {
    active = false
    buf = null
    listener = null
    currentFile = null
    previous = null
    current = null
    stepMoveDist = 0f
    stepNextThreshold = 1f
    wasInWater = false
    smoothedDx = 0.0
    smoothedDz = 0.0
    currentTick = 0
    totalTicks = null
    isFastForwarding = false
    stopAtTick = null
  }

  // Starts playback of `file` and immediately fast-forwards to `targetTick`, synchronously, before
  // returning - used by MarkersScreen to jump straight to a bookmarked moment. This just replays
  // every tick up to the target at once instead of waiting for real ticks (there's no keyframe/
  // snapshot format to seek within), muting sound for the duration (see isFastForwarding).
  fun startAtTick(file: File, targetTick: Int) {
    start(file)
    fastForwardTo(targetTick)
  }

  // Starts playback of `file` and stops it automatically once `endTick` is reached, fast-forwarding
  // (synchronously, same as startAtTick) past everything before `startTick` first. Used by
  // VideoExporter to render just a blueprint's tick range instead of a whole recording.
  fun startRange(file: File, startTick: Int, endTick: Int) {
    start(file)
    stopAtTick = endTick
    if (startTick > 0) fastForwardTo(startTick)
  }

  // Jumps to an arbitrary tick while already watching a recording (as opposed to startAtTick,
  // which only starts one) - the actual "scrubbing" during normal playback. Forward seeks just
  // keep replaying from wherever we already are; backward seeks have to restart from tick 0 and
  // fast-forward back up, same reasoning as startAtTick - there's no way to "un-apply" already
  // -applied block changes, entity spawns etc. without a keyframe format we don't have.
  fun seekTo(targetTick: Int) {
    if (!active) return
    val file = currentFile ?: return
    val clamped = targetTick.coerceAtLeast(0)
    if (clamped < currentTick) {
      // start() re-processes the recording's cached login packet, and vanilla's own
      // ClientPacketListener.handleLogin() unconditionally opens a ReceivingLevelScreen - normally
      // fine for a real, slow network join, but jarring here where everything resolves instantly
      // and synchronously (fastForwardTo, below, doesn't return until it's done) and it stomps
      // whatever screen the caller (e.g. PlaybackTimelineScreen) already had open. Put back whatever
      // was open before the restart, once the whole synchronous rebuild is done.
      val screenBefore = Minecraft.getInstance().screen
      start(file)
      fastForwardTo(clamped)
      Minecraft.getInstance().setScreen(screenBefore)
      return
    }
    fastForwardTo(clamped)
  }

  // Synchronously replays ticks (muting sound - see isFastForwarding) until currentTick reaches
  // targetTick or the recording ends, whichever comes first.
  private fun fastForwardTo(targetTick: Int) {
    isFastForwarding = true
    try {
      while (active && currentTick < targetTick) {
        tick()
      }
    } finally {
      isFastForwarding = false
    }
  }

  // Public: the user is done watching (or the file ran out, or something broke) - actually leave
  // the fake replay world and return to the title screen, the same way the game's own "Save and
  // Quit to Title" / "Disconnect" button does. Without this there was no way back: our synthetic
  // Connection is never truly connected, so the normal disconnect flow has nothing real to react
  // to, and just doing resetState() left the player standing in a frozen, un-exitable world once
  // the recording ran out.
  fun stop() {
    if (!active && buf == null) return
    resetState()

    val mc = Minecraft.getInstance()
    try {
      leaveCurrentWorld(mc)
    } finally {
      mc.setScreen(TitleScreen())
    }
  }

  // Cleanly leaves whatever world/level is currently loaded (real or our own fake replay one),
  // mirroring vanilla's own "Save and Quit to Title" / "Disconnect" logic (see
  // PauseScreen.onDisconnect()) so mc.singleplayerServer never ends up dangling.
  private fun leaveCurrentWorld(mc: Minecraft) {
    if (mc.level == null) return
    mc.level?.disconnect()
    mc.clearLevel()
  }

  // Called from the main client thread at the end of every client tick, while active.
  fun tick() {
    if (tickInProgress) return
    tickInProgress = true
    try {
      tickInternal()
    } finally {
      tickInProgress = false
    }
  }

  private fun tickInternal() {
    val buf = this.buf ?: return
    val handle = this.listener ?: return

    try {
      while (true) {
        if (!buf.isReadable) {
          LOGGER.info("Playback reached end of file")
          stop()
          return
        }

        when (val id = buf.readVarInt()) {
          TICK_END -> {
            currentTick++
            val stopTick = stopAtTick
            if (stopTick != null && currentTick >= stopTick) {
              stop()
            }
            return
          }
          PLAYER_SNAPSHOT -> applySnapshot(buf)
          BLOCK_CHANGE -> applyBlockChange(buf)
          SWING -> applySwing(buf)
          BLOCK_BREAK_PROGRESS -> applyBlockBreakProgress(buf)
          MINING_PARTICLE -> applyMiningParticle(buf)
          LOCAL_LEVEL_EVENT -> applyLocalLevelEvent(buf)
          LOCAL_PLAY_SOUND -> applyLocalPlaySound(buf)
          LOCAL_START_USING_ITEM -> applyStartUsingItem(buf)
          LOCAL_STOP_USING_ITEM -> applyStopUsingItem()
          LOCAL_SNEAK_STATE -> applySneakState(buf)
          LOCAL_SKIN_CUSTOMIZATION -> applySkinCustomization(buf)
          else -> {
            if (id < 0) {
              LOGGER.warn("Unknown record type {} in recording, stopping playback", id)
              stop()
              return
            }
            val length = buf.readVarInt()
            if (buf.readableBytes() < length) {
              // The file ends (or is corrupt) partway through a packet - most likely it was
              // still being recorded when playback started. Stop cleanly instead of crashing.
              LOGGER.warn(
                "Recording ends mid-packet ({} of {} bytes available) - stopping playback",
                buf.readableBytes(),
                length
              )
              stop()
              return
            }
            val packetBuf = FriendlyByteBuf(buf.readBytes(length))
            val packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, packetBuf)
            val isSoundPacket = packet is ClientboundSoundPacket ||
              packet is ClientboundSoundEntityPacket ||
              packet is ClientboundStopSoundPacket
            if (packet == null) {
              LOGGER.warn("Could not reconstruct packet with id {}", id)
            } else if (isFastForwarding && isSoundPacket) {
              // skip - see isFastForwarding
            } else {
              try {
                @Suppress("UNCHECKED_CAST")
                (packet as Packet<PacketListener>).handle(handle.listener)
              } catch (e: Exception) {
                LOGGER.warn("Failed to replay packet {}", packet.javaClass.simpleName, e)
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      LOGGER.warn("Playback failed unexpectedly, stopping", e)
      stop()
    }
  }

  private fun applySnapshot(buf: FriendlyByteBuf) {
    val x = buf.readDouble()
    val y = buf.readDouble()
    val z = buf.readDouble()
    val yaw = buf.readFloat()
    val pitch = buf.readFloat()
    val motionX = buf.readDouble()
    val motionY = buf.readDouble()
    val motionZ = buf.readDouble()
    val onGround = buf.readBoolean()
    val selectedSlot = buf.readVarInt()

    val newState = LookState(x, y, z, yaw, pitch)
    val lastTick = current
    previous = lastTick ?: newState
    current = newState

    val player = Minecraft.getInstance().player ?: return
    player.setDeltaMovement(Vec3(motionX, motionY, motionZ))
    player.setOnGround(onGround)
    player.inventory.selected = selectedSlot

    val level = Minecraft.getInstance().level
    val isInWater = level?.getFluidState(BlockPos.containing(x, y, z))?.`is`(FluidTags.WATER) ?: false
    if (isInWater && !wasInWater && !isFastForwarding) {
      (player as EntityMovementSoundInvokerMixin).`recordingmod$doWaterSplashEffect`()
    }
    wasInWater = isInWater

    if (lastTick != null) {
      // Vanilla's real per-tick contract for xo/yo/zo/xRotO/yRotO is "value at the START of this
      // tick" (set once, before that tick's own movement), left in place for the renderer's own
      // Mth.lerp(partialTick, old, current) to interpolate across every frame of the NEXT tick
      // period - used well beyond just the main position lerp, e.g. CapeLayer's cloak-swing offset
      // (Mth.lerp(h, xo, getX())) and ItemInHandRenderer's hand-bob pitch lerp. onRenderFrame used
      // to overwrite these same fields to always equal the current value on every single render
      // frame (originally to feed our own manual per-frame lerp without vanilla "double
      // interpolating" on top of it) - which broke walk animation (fixed below) but silently left
      // every OTHER consumer of these fields collapsed to a no-op lerp too, since old==current
      // always. Fixed at the root instead of per-symptom: set the real "start of tick" value here,
      // once per tick, and let onRenderFrame (see below) only ever move the *current* value.
      player.xo = lastTick.x
      player.yo = lastTick.y
      player.zo = lastTick.z
      player.xRotO = lastTick.pitch
      player.yRotO = lastTick.yaw

      val dx = newState.x - lastTick.x
      val dz = newState.z - lastTick.z
      val horizontalDist = Mth.sqrt((dx * dx + dz * dz).toFloat())

      // LivingEntity.calculateEntityAnimation() (which drives the third-person model's leg/limb
      // swing animation, via LivingEntityRenderer reading player.walkAnimation) measures movement
      // as getX() - xo each tick - xo is now the real tick-start value (see above), but that alone
      // still isn't enough: Entity.move()'s per-tick call to this is what vanilla actually relies
      // on, and playback never calls it (position is applied by direct teleport/lerp instead). Same
      // root issue as footstep sounds (see maybePlayMovementSound's comment). Fixed the same way:
      // reimplement the relevant bit (LivingEntity.updateWalkAnimation) ourselves, fed by our own
      // already-computed tick-to-tick horizontal distance.
      player.walkAnimation.update(horizontalDist.coerceAtMost(0.25f) * 4f, 0.4f)

      // View bobbing (GameRenderer.bobView) reads walkDist/walkDistO and bob/oBob directly - none
      // of which walkAnimation.update above touches (that's a separate, newer field pair added later
      // just for the third-person leg-swing model). Both are normally maintained by Player.aiStep()
      // (bob/oBob smoothing) and Entity.move() (walkDist accumulation, same *0.6F distance formula
      // as our own footstep accumulator below) - aiStep() is fully cancelled during playback
      // (LocalPlayerAiStepMixin) and move() is never called at all (position is direct teleport/lerp),
      // so without this, bob/oBob/walkDist stay frozen at their initial values forever and the
      // camera never bobs, even with the setting on. Reimplemented the same way as walkAnimation:
      // fed by our own already-known per-tick values instead of vanilla's own physics pass.
      player.walkDistO = player.walkDist
      player.walkDist += horizontalDist * 0.6f
      player.oBob = player.bob
      val bobTarget = if (onGround && !player.isDeadOrDying && !player.isSwimming) {
        player.deltaMovement.horizontalDistance().toFloat().coerceAtMost(0.1f)
      } else {
        0f
      }
      player.bob += (bobTarget - player.bob) * 0.4f

      maybePlayMovementSound(player, horizontalDist, onGround, isInWater)
      updateBodyAndHeadRotation(yaw, dx, dz)
    } else {
      updateBodyAndHeadRotation(yaw, 0.0, 0.0)
    }
  }

  // Reimplements the tick-rate part of LivingEntity.tick()/tickHeadTurn() (both of which we can't
  // reach directly - the real ones live inside aiStep(), which LocalPlayerAiStepMixin cancels
  // entirely during playback). Deliberately only ever writes yBodyRot/yHeadRot themselves, never
  // yBodyRotO/yHeadRotO - vanilla's own (non-cancelled) LivingEntity.tick() already copies
  // "old = current" for both, every tick, before this runs, which is exactly the timing needed for
  // LivingEntityRenderer's own unconditional Mth.rotLerp(partialTick, yBodyRotO, yBodyRot) to
  // interpolate the body/head smoothly across the whole tick - the same way it already does for
  // position (xo/x) - with no per-frame involvement from us needed at all.
  //
  // The target body yaw is vanilla's real one: the direction of actual movement (atan2 of this
  // tick's recorded dx/dz), not the view yaw - vanilla only falls back to view yaw while attacking,
  // and otherwise leaves yBodyRot untouched while standing still (matching the well-known vanilla
  // quirk where your body doesn't turn to face a new look direction until you actually move). Two
  // earlier versions got this wrong in ways that both happened to look smooth in isolation but were
  // never actually right:
  //   1. Forcing yBodyRot=yBodyRotO=yHeadRot=yHeadRotO to the per-frame camera yaw every render
  //      frame threw away yBodyRot's whole reason for existing as a separate, low-pass-filtered
  //      field, feeding every small recorded-yaw fluctuation straight into the body/head model,
  //      frame-for-frame - visible as a "nervous" flickering silhouette (worst at the head, farthest
  //      from the model's rotation pivot at the feet, and against high-contrast backgrounds like
  //      open sky), confirmed absent from the identical recording watched live.
  //   2. Smoothing yBodyRot toward view yaw once per tick (still wrong) fixed the flicker for
  //      straight-line walking - where movement direction and view direction roughly coincide - but
  //      not for strafing, where they don't, which is exactly where this was first reported.
  // Using the real movement-direction target fixes both: since it derives from our own already-
  // recorded per-tick dx/dz (there being no live physics to read it from), it only depends on
  // genuinely one-tick-apart data, so it's exactly as stable as vanilla's own version.
  //
  // Simplification: vanilla also flips the walk animation's sign while facing backward relative to
  // travel. Not reimplemented here - a minor animation quirk, not a smoothness bug.
  private fun updateBodyAndHeadRotation(yaw: Float, dx: Double, dz: Double) {
    val player = Minecraft.getInstance().player ?: return

    // A single tick's own dx/dz is noisy - real per-tick displacement (especially while
    // accelerating, changing direction, or moving diagonally) can vary sharply tick-to-tick even
    // when the player's overall direction of travel is fairly steady, since it's just one instant
    // sample of the movement, not smoothed the way live physics's own momentum/friction would tend
    // to keep it. Averaging a few ticks' worth of displacement before deriving an angle from it
    // (same idea as smoothedDx/Z below) damps that per-tick noise out of the *target* itself,
    // instead of relying solely on the 0.3 approach factor to hide a noisy target after the fact -
    // confirmed via real logged data: yBodyRot swinging over 10+ degrees across a handful of ticks
    // while the recorded view yaw never moved at all, which the approach factor alone only damps,
    // not removes, and which is invisible at exactly 20 FPS (always sampled at the same point in
    // each tick's interpolation window) but fully visible at higher, evenly-sweeping frame rates.
    smoothedDx += (dx - smoothedDx) * 0.15
    smoothedDz += (dz - smoothedDz) * 0.15

    // Ramped, not a hard on/off switch: right as the smoothed speed first crosses the "moving"
    // threshold, the direction derived from it is still mostly stale/zero (the average hasn't
    // caught up yet), so snapping straight to a full 30% approach toward whatever that half-formed
    // target happens to be produces its own one-tick kick right at the start of movement (seen in
    // real logged data: 0deg -> -5deg in a single tick). Scaling the approach factor by how far
    // above the threshold the speed is - zero right at the threshold, full strength once clearly
    // moving - spreads that same eventual correction over several ticks instead.
    val speed = Mth.sqrt((smoothedDx * smoothedDx + smoothedDz * smoothedDz).toFloat())
    if (speed > 0.01f) {
      val moveAngle = Math.toDegrees(Mth.atan2(smoothedDz, smoothedDx)).toFloat() - 90f
      val viewDelta = abs(Mth.wrapDegrees(yaw - moveAngle))
      val bodyYawTarget = if (viewDelta < 95f || viewDelta > 265f) moveAngle else moveAngle - 180f
      val rampedFactor = ((speed - 0.01f) / 0.04f).coerceIn(0f, 1f) * 0.3f
      player.yBodyRot += Mth.wrapDegrees(bodyYawTarget - player.yBodyRot) * rampedFactor
    }

    val headBodyDiff = Mth.wrapDegrees(yaw - player.yBodyRot)
    if (abs(headBodyDiff) > 50f) {
      player.yBodyRot += headBodyDiff - Mth.sign(headBodyDiff.toDouble()) * 50f
    }
    // yHeadRot itself is deliberately NOT set here - see onRenderFrame, which tracks it to the
    // camera yaw every render frame instead of once per tick.
  }

  // Reimplements just enough of Entity.move()'s footstep/swim-sound-triggering logic (see the
  // invoker mixin's own comment for why) to make walking and swimming during playback audible
  // again: accumulate horizontal distance moved since the last tick, and once it crosses the
  // threshold, play the real step or swim sound via the invoker mixin - mirroring the moveDist/
  // nextStep bookkeeping Entity.move() does internally for the same purpose.
  private fun maybePlayMovementSound(player: Player, horizontalDist: Float, onGround: Boolean, isInWater: Boolean) {
    stepMoveDist += horizontalDist * 0.6f
    if (stepMoveDist <= stepNextThreshold) return
    stepNextThreshold = stepMoveDist.toInt() + 1f
    if (isFastForwarding) return

    val invoker = player as EntityMovementSoundInvokerMixin
    if (isInWater) {
      invoker.`recordingmod$waterSwimSound`()
      return
    }
    if (!onGround) return

    val level = Minecraft.getInstance().level ?: return
    val pos = player.onPos
    val state = level.getBlockState(pos)
    if (!state.isAir) {
      invoker.`recordingmod$playStepSound`(pos, state)
    }
  }

  // Called every render frame (not just every tick) via GameRendererMixin, before the camera
  // reads the player's position/rotation. We interpolate between the last two recorded tick
  // snapshots ourselves and write the result into the "current" position/rotation fields, so
  // every reader (camera, arm, hitbox outlines, ...) sees an already-smoothed value instead of a
  // value that jumps once every tick (20 times a second). Deliberately does NOT also touch the
  // "old" fields (xo/yo/zo/xRotO/yRotO) every frame anymore - those are now set once per tick, in
  // applySnapshot, to the real "position at start of this tick" vanilla itself expects (see the
  // comment there). Forcing old==current every frame used to silently disable any OTHER vanilla
  // code that does its own Mth.lerp(partialTick, old, current) - not just our own camera lerp
  // above it - which is exactly what broke walk animation (fixed separately) and would equally
  // affect e.g. CapeLayer's cloak-swing offset or ItemInHandRenderer's hand-bob pitch lerp.
  fun onRenderFrame(partialTick: Float) {
    if (!active) return

    // During a fast (as-fast-as-possible) export, VideoExporter drives our tick() directly - not
    // at Minecraft's real ~20/sec pace, which is also what the real partialTick argument is timed
    // against, so it no longer means anything useful for us. nextRenderFrameFraction() ticks us
    // forward as needed for this frame (possibly updating previous/current below) and returns the
    // correct in-between fraction itself, so read it *before* previous/current rather than using
    // the real partialTick.
    val effectivePartialTick = if (VideoExporter.active) VideoExporter.nextRenderFrameFraction() else partialTick

    val from = previous ?: return
    val to = current ?: return
    val player = Minecraft.getInstance().player ?: return

    val x = Mth.lerp(effectivePartialTick.toDouble(), from.x, to.x)
    val y = Mth.lerp(effectivePartialTick.toDouble(), from.y, to.y)
    val z = Mth.lerp(effectivePartialTick.toDouble(), from.z, to.z)
    val yaw = Mth.rotLerp(effectivePartialTick, from.yaw, to.yaw)
    val pitch = Mth.lerp(effectivePartialTick, from.pitch, to.pitch)

    player.setPos(x, y, z)
    player.setYRot(yaw)
    player.setXRot(pitch)
    // Pitch has no head-only equivalent of yHeadRot (see below) - Camera.setup, ItemInHandRenderer's
    // first-person hand/weapon-sway transform, and LivingEntityRenderer's third-person head tilt all
    // read the base xRot/xRotO pair directly via Entity.getViewXRot(partialTick), which does its OWN
    // Mth.lerp(partialTick, xRotO, xRot) - a second interpolation on top of the value we already
    // computed above. Since xRotO is (correctly, per applySnapshot's comment) only updated once per
    // tick while `pitch` here changes every render frame, that second lerp doesn't reproduce our
    // linear interpolation - it composes into partialTick² (verified algebraically: substituting our
    // own lerp result back into Mth.lerp(f, xRotO, ourLerp(f, xRotO, target)) collapses to
    // xRotO + f²*(target - xRotO)), i.e. pitch visibly lags behind the true position during a fast
    // turn before snapping to catch up right at the tick boundary - most noticeable in exactly the
    // first-person held-item transform the user reported ("hands" drifting from where they should be
    // while turning). Fixed the same way yHeadRot already is below: collapse xRotO to the same
    // already-correct value every frame, so any consumer's own additional lerp is a no-op instead of
    // a second interpolation. Yaw doesn't need this - LivingEntity.getViewYRot always reads
    // yHeadRot/yHeadRotO instead of the base yRot/yRotO pair, and those are already collapsed below.
    player.xRotO = pitch

    // yHeadRot tracks the camera yaw every render frame, same as yRot above - the head is
    // *supposed* to follow view direction immediately and exactly, with no per-tick lag, the same
    // way it does live (this is the "your head turns as fast as you can move the mouse" feel).
    // yBodyRot is the opposite: a genuinely low-pass-filtered value, correctly updated only once
    // per tick in updateBodyAndHeadRotation and left for LivingEntityRenderer's own unconditional
    // Mth.rotLerp(partialTick, yBodyRotO, yBodyRot) to interpolate smoothly across the tick, same
    // as position - forcing IT to a per-frame value was the original (already-fixed) mistake. The
    // two fields need opposite treatment because they mean opposite things: the head is meant to
    // be as instantaneous as the camera; the body is deliberately not.
    player.yHeadRot = yaw
    player.yHeadRotO = yaw
  }

  private fun applyBlockChange(buf: FriendlyByteBuf) {
    val pos = buf.readBlockPos()
    val stateId = buf.readVarInt()
    val level = Minecraft.getInstance().level ?: return
    level.setBlock(pos, Block.stateById(stateId), 3)
  }

  private fun applySwing(buf: FriendlyByteBuf) {
    val handOrdinal = buf.readVarInt()
    val hand = InteractionHand.values().getOrNull(handOrdinal) ?: InteractionHand.MAIN_HAND
    Minecraft.getInstance().player?.swing(hand)
  }

  // Restores the local player's own using-item state (see RecordingFormat.LOCAL_START_USING_ITEM)
  // by driving Minecraft's real item-use system directly, rather than reimplementing the bow/food/
  // shield draw animation ourselves - startUsingItem() sets LocalPlayer's own isUsingItem()/
  // getUseItem()/getUseItemRemainingTicks(), which LivingEntity.tick()'s updatingUsingItem() (not
  // cancelled by LocalPlayerAiStepMixin - it lives in tick(), not aiStep()) then progresses every
  // tick exactly as it would live, driving the real draw/charge animation on its own.
  private fun applyStartUsingItem(buf: FriendlyByteBuf) {
    val handOrdinal = buf.readVarInt()
    val hand = InteractionHand.values().getOrNull(handOrdinal) ?: InteractionHand.MAIN_HAND
    Minecraft.getInstance().player?.startUsingItem(hand)
  }

  private fun applyStopUsingItem() {
    Minecraft.getInstance().player?.stopUsingItem()
  }

  // Restores the local player's sneak state (see RecordingFormat.LOCAL_SNEAK_STATE). Turns out
  // LocalPlayer overrides BOTH isShiftKeyDown() and isCrouching() to read purely local, input-driven
  // fields instead of the base Entity/Pose machinery - the exact same "client-prediction override"
  // pattern as isUsingItem() (point 32), just two fields deep instead of one, found by tracing why a
  // debug log showed isShiftKeyDown() reading false immediately after Entity.setShiftKeyDown(true):
  //   - LocalPlayer.isShiftKeyDown() -> `this.input != null && this.input.shiftKeyDown` (ignores the
  //     base entity-data flag entirely) - this is what Player.updatePlayerPose() actually calls via
  //     virtual dispatch, so the base setShiftKeyDown() call below never influenced pose at all; the
  //     CROUCHING pose the earlier debug log caught was vanilla's own "can't fit standing" auto-crouch
  //     fallback (canEnterPose(STANDING) failing), unrelated to the recorded sneak key.
  //   - LocalPlayer.isCrouching() -> `this.crouching`, a separate private field normally only
  //     recomputed inside aiStep() (cancelled during playback, LocalPlayerAiStepMixin) - this is what
  //     PlayerRenderer.setModelProperties reads for the actual bent-over model animation
  //     (playerModel.crouching) and getRenderOffset()'s -0.125 sneak offset, independent of Pose.
  // Setting both directly (input.shiftKeyDown is a public field; crouching needs the accessor mixin,
  // it's private) makes both the pose (camera/hitbox) and the model animation correct, without
  // needing aiStep()'s full derivation - we already know the real answer from the recording.
  // The base setShiftKeyDown() call is kept too, purely for correctness elsewhere (e.g. any other
  // code that reads the synced flag directly rather than through LocalPlayer's overrides).
  private fun applySneakState(buf: FriendlyByteBuf) {
    val sneaking = buf.readBoolean()
    val player = Minecraft.getInstance().player ?: return
    player.setShiftKeyDown(sneaking)
    player.input?.shiftKeyDown = sneaking
    (player as LocalPlayerCrouchingAccessorMixin).`recordingmod$setCrouching`(sneaking)
  }

  // Restores which skin overlay layers (jacket/sleeves/pants legs/hat) render (see RecordingFormat.
  // LOCAL_SKIN_CUSTOMIZATION) - there's no public Player API for this, so it's set directly on the
  // synced entity-data byte via the accessor mixin (mirrors exactly how ServerPlayer itself sets it
  // when handling a real client's ServerboundClientInformationPacket).
  private fun applySkinCustomization(buf: FriendlyByteBuf) {
    val mask = buf.readByte()
    val player = Minecraft.getInstance().player ?: return
    player.entityData.set(PlayerModelCustomisationAccessorMixin.`recordingmod$dataPlayerModeCustomisation`(), mask)
  }

  private fun applyBlockBreakProgress(buf: FriendlyByteBuf) {
    val breakerId = buf.readVarInt()
    val pos = buf.readBlockPos()
    val progress = buf.readVarInt()
    Minecraft.getInstance().level?.destroyBlockProgress(breakerId, pos, progress)
  }

  private fun applyMiningParticle(buf: FriendlyByteBuf) {
    val pos = buf.readBlockPos()
    val direction = Direction.from3DDataValue(buf.readVarInt())
    Minecraft.getInstance().particleEngine.crack(pos, direction)
  }

  private fun applyLocalLevelEvent(buf: FriendlyByteBuf) {
    val type = buf.readVarInt()
    val pos = buf.readBlockPos()
    val data = buf.readVarInt()
    if (isFastForwarding) return
    val level = Minecraft.getInstance().level ?: return
    val player = Minecraft.getInstance().player
    level.levelEvent(player, type, pos, data)
  }

  private fun applyLocalPlaySound(buf: FriendlyByteBuf) {
    val pos = buf.readBlockPos()
    val location = buf.readResourceLocation()
    val source = buf.readEnum(SoundSource::class.java)
    val volume = buf.readFloat()
    val pitch = buf.readFloat()
    if (isFastForwarding) return
    val level = Minecraft.getInstance().level ?: return
    val player = Minecraft.getInstance().player
    val sound = SoundEvent.createVariableRangeEvent(location)
    level.playSound(player, pos, sound, source, volume, pitch)
  }

  private class ClientPacketListenerHandle(
    val connection: Connection,
    val listener: net.minecraft.client.multiplayer.ClientPacketListener
  )
}
