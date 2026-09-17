package me.aris.recordingmod

import me.aris.recordingmod.RecordingFormat.BLOCK_BREAK_PROGRESS
import me.aris.recordingmod.RecordingFormat.BLOCK_CHANGE
import me.aris.recordingmod.RecordingFormat.LOCAL_LEVEL_EVENT
import me.aris.recordingmod.RecordingFormat.LOCAL_PLAY_SOUND
import me.aris.recordingmod.RecordingFormat.MINING_PARTICLE
import me.aris.recordingmod.RecordingFormat.PLAYER_SNAPSHOT
import me.aris.recordingmod.RecordingFormat.SWING
import me.aris.recordingmod.RecordingFormat.TICK_END
import me.aris.recordingmod.RecordingFormat.newBuffer
import me.aris.recordingmod.RecordingFormat.toByteArray
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet

// Captures the real clientbound PLAY packet stream (via ConnectionMixin) plus a per-tick snapshot
// of our own player's position/look/motion (which is never sent to us via clientbound packets,
// since movement packets only cover *other* entities).
object RecordingManager {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/recording")
  private val lock = Any()

  @Volatile
  var active = false
    private set

  // Exposed for the marker feature (see MarkerManager) - marking a moment needs to know which
  // file is currently being recorded and how many ticks into it we are, neither of which is
  // otherwise tracked anywhere once a tick's records have been written.
  var currentFile: File? = null
    private set

  @Volatile
  var currentTick = 0
    private set

  private var out: BufferedOutputStream? = null

  // The most recent ClientboundLoginPacket, and (if it happened after that login, e.g. a
  // dimension change) the most recent ClientboundRespawnPacket - tracked at ALL times, not just
  // while active=true. If recording is toggled on mid-session - the normal case, since you're
  // already in the world before you press the key - the real login packet was never captured,
  // so playback would never learn which level/dimension/entities to create (a respawn packet
  // alone can't bootstrap a level; it assumes one already exists). Caching these lets us splice
  // them in, in order, as the first record(s) whenever a new recording starts.
  private var cachedLoginPacket: ClientboundLoginPacket? = null
  private var cachedRespawnPacket: ClientboundRespawnPacket? = null
  // Sent by the server exactly once (or rarely, e.g. after sleeping in a bed), and required by
  // ReceivingLevelScreen to ever dismiss itself (see handleSetSpawn). Without it, starting
  // playback gets stuck forever on the "Loading terrain" screen since it never learns that the
  // initial loading packets have arrived - same caching story as login/respawn above.
  private var cachedSpawnPositionPacket: ClientboundSetDefaultSpawnPositionPacket? = null

  fun start(file: File) {
    synchronized(lock) {
      stopInternal()
      file.parentFile?.mkdirs()
      out = BufferedOutputStream(FileOutputStream(file))
      active = true
      currentFile = file
      currentTick = 0
      cachedLoginPacket?.let { writePacket(it) }
      cachedRespawnPacket?.let { writePacket(it) }
      cachedSpawnPositionPacket?.let { writePacket(it) }
      writeChunkSnapshot()
      writeEntitySnapshot()
      writeInventorySnapshot()
      LOGGER.info("Started recording to {}", file)
    }
  }

  fun stop() {
    synchronized(lock) {
      stopInternal()
    }
  }

  private fun stopInternal() {
    active = false
    currentFile = null
    out?.let {
      it.flush()
      it.close()
    }
    out = null
  }

  // Called from ConnectionMixin, on the network (Netty) thread - NOT the main client thread.
  //
  // Deliberately does NOT check "is this Minecraft.getInstance().connection's Connection" -
  // that getter derives from `player.connection`, which doesn't exist until AFTER the very
  // first PLAY packet (ClientboundLoginPacket) has already been handled. Checking it here would
  // silently discard the login packet itself on every single recording. There is only ever one
  // real PLAY connection going through this client at a time (our own synthetic playback feeds
  // packets straight into a listener and never touches Connection.channelRead0), so filtering by
  // protocol alone is sufficient.
  fun onPacketReceived(connection: Connection, packet: Packet<*>) {
    if (ConnectionProtocol.getProtocolForPacket(packet) !== ConnectionProtocol.PLAY) return

    // Singleplayer's local (in-memory) Connection pipeline has no "unbundler" stage (that only
    // gets added for a real networked connection - see Connection.configureSerialization vs.
    // connectToLocalServer), so bundled packets (used e.g. to spawn a projectile and set its
    // entity data in the same instant, avoiding a one-frame glitch) arrive here as a single
    // ClientboundBundlePacket wrapper instead of being pre-split into its parts. It isn't in
    // ConnectionProtocol's id table (only its *contents* are), so writePacket's getPacketId
    // lookup silently returned -1 and the whole bundle - e.g. an arrow's spawn packet - was
    // dropped without a trace. Unwrap it ourselves and recurse on each real sub-packet instead.
    if (packet is BundlePacket<*>) {
      for (subPacket in packet.subPackets()) {
        onPacketReceived(connection, subPacket)
      }
      return
    }

    if (packet is ClientboundLoginPacket) {
      cachedLoginPacket = packet
      cachedRespawnPacket = null
      cachedSpawnPositionPacket = null
    } else if (packet is ClientboundRespawnPacket) {
      cachedRespawnPacket = packet
    } else if (packet is ClientboundSetDefaultSpawnPositionPacket) {
      cachedSpawnPositionPacket = packet
    }

    if (!active) return
    synchronized(lock) {
      if (out != null) writePacket(packet)
    }
  }

  // Caller must hold `lock`. Chunk data (like login/respawn/spawn-position) is normally sent by
  // the server only once, as the player gets close enough to see it - if recording starts
  // mid-session, none of the chunks around the player were ever captured, so on playback there
  // is no terrain at all near the player and ReceivingLevelScreen never finds a compiled chunk
  // to dismiss itself on (it waits forever, until its own 30s timeout). Synthesizing a
  // ClientboundLevelChunkWithLightPacket for every chunk the client already has loaded - exactly
  // the same packet a server builds when a chunk enters view distance - fixes both problems at
  // once: playback gets terrain immediately, and the loading screen can dismiss normally.
  private fun writeChunkSnapshot() {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: return
    val player = mc.player ?: return
    val lightEngine = level.lightEngine
    val chunkSource = level.chunkSource
    val center = player.chunkPosition()

    // ClientChunkCache tracks a "view center" (defaults to chunk 0,0) and silently drops any
    // chunk packet further from it than the chunk radius ("Ignoring chunk since it's not in the
    // view range"). Normally ClientboundSetChunkCacheCenterPacket keeps this in sync as the
    // player moves; synthesize one for the player's current chunk so our snapshot packets below
    // are actually accepted instead of being dropped.
    writePacket(ClientboundSetChunkCacheCenterPacket(center.x, center.z))
    // Must NOT exceed the radius ClientChunkCache's storage array was actually sized for
    // (derived from the login packet's chunkRadius, which normally matches render distance in
    // singleplayer) - anything further out gets silently dropped the same way as before ("not
    // in view range"), which showed up as holes/missing blocks at the edges of the snapshot.
    val radius = mc.options.renderDistance().get() ?: 8

    for (dx in -radius..radius) {
      for (dz in -radius..radius) {
        val chunk = chunkSource.getChunk(center.x + dx, center.z + dz, false) ?: continue
        writePacket(ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null))
      }
    }
  }

  // Caller must hold `lock`. Same "sent once, before recording started" problem as chunks, but
  // for entities: other players and mobs that already existed are missing entirely from a
  // mid-session recording, since ClientboundAddEntityPacket/ClientboundAddPlayerPacket only ever
  // go out once, when the entity first enters view. Symptoms this caused: other players/mobs
  // never appearing (or existing but jerky, if only later movement/metadata packets - which
  // reference their entity id - happened to get captured live), and missing hurt/death
  // animations for them (those handlers do `level.getEntity(id)` and silently no-op if it's
  // null). ClientboundAddPlayerPacket additionally requires that player's PlayerInfo (name/skin/
  // gamemode - from ClientboundPlayerInfoUpdatePacket, itself a "sent once" packet) to already be
  // known, or ClientPacketListener.handleAddPlayer silently refuses to spawn them - so that has
  // to be synthesized too.
  private fun writeEntitySnapshot() {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: return
    val myId = mc.player?.id
    val connection = mc.connection

    if (connection != null) {
      for (info in connection.listedOnlinePlayers) {
        writePacket(synthesizePlayerInfoPacket(info))
      }
    }

    for (entity in level.entitiesForRendering()) {
      if (entity.id == myId) continue

      if (entity is Player) {
        writePacket(ClientboundAddPlayerPacket(entity))
      } else {
        writePacket(ClientboundAddEntityPacket(entity))
      }

      val data = entity.entityData.nonDefaultValues
      if (data != null) {
        writePacket(ClientboundSetEntityDataPacket(entity.id, data))
      }
    }
  }

  // Caller must hold `lock`. Same "sent once at join" problem as everything else here: the
  // player's inventory/hotbar contents are sent once via ClientboundContainerSetContentPacket
  // and then only ever updated incrementally (single-slot ClientboundContainerSetSlotPacket on
  // pickup/drop/etc.), never resent in full. If recording starts mid-session, the fake listener's
  // inventory starts out empty, so the currently-selected slot (which our own per-tick snapshot
  // does track correctly) points at nothing - the held item renders as empty, and anything that
  // depends on the actual item (eating/drinking/blocking/bow-draw animations, item-colored
  // particles) can't work either.
  private fun writeInventorySnapshot() {
    val player = Minecraft.getInstance().player ?: return
    val menu = player.inventoryMenu
    writePacket(
      ClientboundContainerSetContentPacket(menu.containerId, menu.stateId, menu.items, menu.carried)
    )
  }

  // Builds a real ClientboundPlayerInfoUpdatePacket from client-side PlayerInfo. There is no
  // public constructor for this from client-only data (the real one needs a server-side
  // ServerPlayer we don't have) - so we hand-encode the same bytes the server would have sent
  // (matching Action's private writers) and decode them back through the packet's own, public,
  // FriendlyByteBuf constructor. That gives us a genuine, fully-functional packet instance
  // without needing reflection.
  private fun synthesizePlayerInfoPacket(info: PlayerInfo): ClientboundPlayerInfoUpdatePacket {
    val actions = EnumSet.of(
      ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
      ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
      ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
      ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
      ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
      ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
    )

    val buf = newBuffer()
    buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action::class.java)
    buf.writeCollection(listOf(info)) { b: FriendlyByteBuf, i: PlayerInfo ->
      b.writeUUID(i.profile.id)
      // ADD_PLAYER
      b.writeUtf(i.profile.name, 16)
      b.writeGameProfileProperties(i.profile.properties)
      // INITIALIZE_CHAT - no chat session to restore, nothing to sign messages with anyway.
      b.writeNullable<Any>(null) { _, _ -> }
      // UPDATE_GAME_MODE
      b.writeVarInt(i.gameMode.id)
      // UPDATE_LISTED
      b.writeBoolean(true)
      // UPDATE_LATENCY
      b.writeVarInt(i.latency)
      // UPDATE_DISPLAY_NAME
      b.writeNullable(i.tabListDisplayName) { b2: FriendlyByteBuf, comp -> b2.writeComponent(comp) }
    }

    return ClientboundPlayerInfoUpdatePacket(buf)
  }

  // Caller must hold `lock` and have already checked `out != null`.
  private fun writePacket(packet: Packet<*>) {
    val id = ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet)
    if (id < 0) return

    val payload = newBuffer()
    try {
      packet.write(payload)
    } catch (e: Exception) {
      LOGGER.warn("Failed to encode packet {} for recording", packet.javaClass.simpleName, e)
      return
    }
    val payloadBytes = payload.toByteArray()

    val header = newBuffer()
    header.writeVarInt(id)
    header.writeVarInt(payloadBytes.size)

    val stream = out ?: return
    stream.write(header.toByteArray())
    stream.write(payloadBytes)
  }

  // Called from LevelMixin whenever a block change is actually applied on the client level -
  // this covers both server-driven changes (also captured redundantly via packets above) and,
  // crucially, our own client-predicted block breaks/places which never arrive as a packet.
  fun onBlockChanged(level: Level, pos: BlockPos, state: BlockState) {
    if (!active) return
    if (level !== Minecraft.getInstance().level) return

    val buf = newBuffer()
    buf.writeVarInt(BLOCK_CHANGE)
    buf.writeBlockPos(pos)
    buf.writeVarInt(Block.getId(state))

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from LivingEntitySwingMixin whenever an entity starts an arm swing. Only the local
  // player matters here - other entities' swings already arrive via ClientboundAnimatePacket.
  fun onSwing(entity: LivingEntity, hand: InteractionHand) {
    if (!active) return
    if (entity !== Minecraft.getInstance().player) return

    val buf = newBuffer()
    buf.writeVarInt(SWING)
    buf.writeVarInt(hand.ordinal)

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from ClientLevelMixin whenever the block-breaking "crack" overlay is started,
  // advanced or stopped. Only our own breaking matters - other players' progress already
  // arrives via ClientboundBlockDestructionPacket.
  fun onBlockBreakProgress(level: ClientLevel, breakerId: Int, pos: BlockPos, progress: Int) {
    if (!active) return
    if (level !== Minecraft.getInstance().level) return
    val player = Minecraft.getInstance().player ?: return
    if (breakerId != player.id) return

    val buf = newBuffer()
    buf.writeVarInt(BLOCK_BREAK_PROGRESS)
    buf.writeVarInt(breakerId)
    buf.writeBlockPos(pos)
    buf.writeVarInt(progress)

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from ParticleEngineMixin every tick while mining a block. Always local (only ever
  // called for the player doing the mining), so no identity check is needed here.
  fun onMiningParticle(pos: BlockPos, direction: Direction) {
    if (!active) return

    val buf = newBuffer()
    buf.writeVarInt(MINING_PARTICLE)
    buf.writeBlockPos(pos)
    buf.writeVarInt(direction.get3DDataValue())

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from ClientLevelMixin's hook on ClientLevel.levelEvent(Player, ...) - see
  // RecordingFormat.LOCAL_LEVEL_EVENT for why this needs its own capture. Only the local player's
  // own predicted events matter here; a real server-driven ClientboundLevelEventPacket (e.g. some
  // other entity destroying a block) already replays fine as an ordinary packet and would arrive
  // via a different, 3-arg overload we don't hook.
  fun onLocalLevelEvent(player: Player?, type: Int, pos: BlockPos, data: Int) {
    if (!active) return
    if (player !== Minecraft.getInstance().player) return

    val buf = newBuffer()
    buf.writeVarInt(LOCAL_LEVEL_EVENT)
    buf.writeVarInt(type)
    buf.writeBlockPos(pos)
    buf.writeVarInt(data)

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from LevelPlaySoundMixin's hook on Level.playSound(Player, BlockPos, ...) - see
  // RecordingFormat.LOCAL_PLAY_SOUND for why this needs its own capture (e.g. BlockItem.place()'s
  // block-place sound). Only the local player's own predicted sounds matter here, and only on the
  // client - the same Level method is also reachable on a real ServerLevel in singleplayer, since
  // client and integrated server share one classloader.
  fun onLocalPlaySound(
    level: Level,
    player: Player?,
    pos: BlockPos,
    sound: SoundEvent,
    source: SoundSource,
    volume: Float,
    pitch: Float
  ) {
    if (!active) return
    if (!level.isClientSide) return
    if (player !== Minecraft.getInstance().player) return

    val buf = newBuffer()
    buf.writeVarInt(LOCAL_PLAY_SOUND)
    buf.writeBlockPos(pos)
    buf.writeResourceLocation(sound.location)
    buf.writeEnum(source)
    buf.writeFloat(volume)
    buf.writeFloat(pitch)

    synchronized(lock) {
      val stream = out ?: return
      stream.write(buf.toByteArray())
    }
  }

  // Called from the main client thread at the end of every client tick.
  fun onClientTick() {
    if (!active) return
    val player = Minecraft.getInstance().player ?: return

    val snapshot = newBuffer()
    snapshot.writeVarInt(PLAYER_SNAPSHOT)
    snapshot.writeDouble(player.x)
    snapshot.writeDouble(player.y)
    snapshot.writeDouble(player.z)
    snapshot.writeFloat(player.yRot)
    snapshot.writeFloat(player.xRot)
    val motion = player.deltaMovement
    snapshot.writeDouble(motion.x)
    snapshot.writeDouble(motion.y)
    snapshot.writeDouble(motion.z)
    snapshot.writeBoolean(player.onGround())
    snapshot.writeVarInt(player.inventory.selected)

    val tickEnd = newBuffer()
    tickEnd.writeVarInt(TICK_END)

    synchronized(lock) {
      val stream = out ?: return
      stream.write(snapshot.toByteArray())
      stream.write(tickEnd.toByteArray())
    }
    currentTick++
  }
}
