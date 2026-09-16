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

  fun newBuffer(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

  fun ByteBuf.toByteArray(): ByteArray {
    val array = ByteArray(this.readableBytes())
    this.getBytes(this.readerIndex(), array)
    return array
  }
}
