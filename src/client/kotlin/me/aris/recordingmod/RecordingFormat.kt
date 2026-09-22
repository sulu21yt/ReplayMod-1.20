package me.aris.recordingmod

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf

// Recording file format (v1, milestone 1 - not yet optimized/streamed):
//
// The file is a sequence of records. Each record starts with a varint "type":
//   >= 0        -> a raw clientbound PLAY packet, identified by its ConnectionProtocol.PLAY packet id.
//                  Followed by varint payload length, then that many bytes (the packet's own write() encoding).
//   PLAYER_SNAPSHOT -> our own player's position/look/motion for the current tick (see writePlayerSnapshot).
//   TICK_END    -> marks the end of a client tick. Playback stops consuming records until the next
//                  real client tick so recording and playback both advance one Minecraft tick at a time.
object RecordingFormat {
  const val TICK_END = -1
  const val PLAYER_SNAPSHOT = -2
  // A block change that happened directly on the client level (BlockPos + block state id),
  // not via a clientbound packet. Needed because breaking/placing blocks yourself is
  // client-predicted: the client applies the change locally and the server only sends a
  // lightweight acknowledgement, not a block-update packet.
  const val BLOCK_CHANGE = -3
  // The local player swinging an arm (attacking, mining, eating, using an item, ...). Same
  // story as block changes: the server never echoes your own swing back to you as a packet
  // (ClientboundAnimatePacket is only broadcast to *other* players), so without this the
  // recording player's own arm/attack animations never play back.
  const val SWING = -4
  // Local block-breaking "crack" overlay progress (started/continued/stopped mining a block).
  // Same client-prediction story as SWING/BLOCK_CHANGE.
  const val BLOCK_BREAK_PROGRESS = -5
  // The dust particles spawned each tick while mining a block (ParticleEngine.crack). This is
  // separate from the crack overlay above and from the final "block destroyed" burst (which IS
  // a real ClientboundLevelEventPacket and already replays fine) - it's triggered purely from
  // local input handling (Minecraft.continueAttack), so it never reaches us as a packet either.
  const val MINING_PARTICLE = -6
  // A ClientLevel.levelEvent(Player, type, pos, data) call attributed to the local player - e.g.
  // the block-break sound+particle burst fired directly by Block.spawnDestroyParticles when WE
  // predict breaking a block. Despite looking just like a real level event, this is purely local:
  // ClientLevel.levelEvent(Player, ...) never goes through the network in either direction (see
  // RecordingManager.onLocalLevelEvent), unlike a genuine ClientboundLevelEventPacket from the
  // server (which already replays fine as an ordinary packet and is unaffected by this).
  const val LOCAL_LEVEL_EVENT = -7
  // A Level.playSound(Player, pos, sound, source, volume, pitch) call attributed to the local
  // player - e.g. the block-place sound fired directly by BlockItem.place(). Same story as
  // LOCAL_LEVEL_EVENT above: on the client this only ever plays for player == mc.player and never
  // touches the network (see RecordingManager.onLocalPlaySound).
  const val LOCAL_PLAY_SOUND = -8
  // The local player starting to actively use an item (bow/crossbow draw, eating, drinking,
  // blocking, trident) - InteractionHand ordinal follows. LocalPlayer.isUsingItem() reads a purely
  // local field set only by LocalPlayer.startUsingItem()/stopUsingItem(), never by a packet: the
  // server never sends a player their OWN using-item entity-data flag back (ChunkMap.TrackedEntity
  // deliberately excludes a player from its own set of tracked viewers). Same client-prediction
  // story as SWING, just for a start/stop pair instead of an instant - see
  // RecordingManager.onLocalStartUsingItem/onLocalStopUsingItem.
  const val LOCAL_START_USING_ITEM = -9
  // The local player stopping active item use (release, or interrupted). See LOCAL_START_USING_ITEM.
  const val LOCAL_STOP_USING_ITEM = -10
  // The local player's current sneak (shift) key state, written every tick alongside
  // PLAYER_SNAPSHOT. Sneaking is server-authoritative in vanilla (Entity.setShiftKeyDown is only
  // ever called from ServerGamePacketListenerImpl, in response to a ServerboundPlayerCommandPacket
  // the client sends when the key is pressed/released) and vanilla's own server does echo the
  // resulting entity-data flag change back to the owning player too (ServerEntity.sendDirtyEntityData
  // uses broadcastAndSend, not plain broadcast - unlike position/rotation updates, which really are
  // self-excluded). But that's an implementation detail of vanilla's own server, not something this
  // mod can rely on for every server it might record on (e.g. a heavily customized one like Hypixel
  // may not bother echoing a player's own state back to themselves, same as this mod's LOCAL_START_
  // USING_ITEM/LOCAL_STOP_USING_ITEM point 32 already had to stop depending on the equivalent packet
  // existing at all). Capturing our own read of the live key state directly - like a client-predicted
  // action - is self-contained and doesn't depend on what a particular server chooses to send back.
  const val LOCAL_SNEAK_STATE = -11
  // The local player's currently-enabled skin layers (jacket, sleeves, pants legs, hat - the
  // "Skin Customization" options screen), written every tick alongside PLAYER_SNAPSHOT. There is no
  // public API on Player to set this at all (Player.isModelPartShown reads a synced entity-data byte
  // that only ServerPlayer ever writes, handling a real client's ServerboundClientInformationPacket)
  // and it's only ever sent to the server once - on join, or when the player changes the setting -
  // same "sent once near join" story as login/respawn/spawn-position/tags (points 8-9), so a
  // mid-session recording never captures it via normal packet replay. Symptom without this: base
  // skin renders but every overlay layer (jacket/sleeves/pants/hat) is invisible during playback,
  // since Player's entity data defaults this byte to 0 (nothing shown) until told otherwise.
  const val LOCAL_SKIN_CUSTOMIZATION = -12

  fun newBuffer(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

  fun ByteBuf.toByteArray(): ByteArray {
    val array = ByteArray(this.readableBytes())
    this.getBytes(this.readerIndex(), array)
    return array
  }
}
