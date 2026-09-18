package me.aris.recordingmod

import io.netty.buffer.Unpooled
import me.aris.recordingmod.RecordingFormat.BLOCK_BREAK_PROGRESS
import me.aris.recordingmod.RecordingFormat.BLOCK_CHANGE
import me.aris.recordingmod.RecordingFormat.LOCAL_LEVEL_EVENT
import me.aris.recordingmod.RecordingFormat.LOCAL_PLAY_SOUND
import me.aris.recordingmod.RecordingFormat.MINING_PARTICLE
import me.aris.recordingmod.RecordingFormat.PLAYER_SNAPSHOT
import me.aris.recordingmod.RecordingFormat.SWING
import me.aris.recordingmod.RecordingFormat.TICK_END
import me.aris.recordingmod.mixins.EntityMovementSoundInvokerMixin
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

  // How many ticks of this playback we've processed so far - used by startAtTick to know when
  // it's caught up to the requested marker tick.
  @Volatile
  var currentTick = 0
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
    currentTick = 0
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
      start(file)
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
      maybePlayMovementSound(player, lastTick, newState, onGround, isInWater)
    }
  }

  // Reimplements just enough of Entity.move()'s footstep/swim-sound-triggering logic (see the
  // invoker mixin's own comment for why) to make walking and swimming during playback audible
  // again: accumulate horizontal distance moved since the last tick, and once it crosses the
  // threshold, play the real step or swim sound via the invoker mixin - mirroring the moveDist/
  // nextStep bookkeeping Entity.move() does internally for the same purpose.
  private fun maybePlayMovementSound(player: Player, from: LookState, to: LookState, onGround: Boolean, isInWater: Boolean) {
    val dx = to.x - from.x
    val dz = to.z - from.z
    val horizontalDist = Mth.sqrt((dx * dx + dz * dz).toFloat())
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
  // snapshots ourselves and write the result straight into both the "current" and "old" fields,
  // so every reader (camera, arm, hitbox outlines, ...) sees an already-smoothed value instead of
  // a value that jumps once every tick (20 times a second).
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
    player.xo = x
    player.yo = y
    player.zo = z
    player.setYRot(yaw)
    player.setXRot(pitch)
    player.yRotO = yaw
    player.xRotO = pitch
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
