package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
  @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
  private void recordingmod$onDestroyBlockProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
    RecordingManager.INSTANCE.onBlockBreakProgress((ClientLevel) (Object) this, breakerId, pos, progress);
  }

  // ClientLevel.levelEvent(Player, ...) is how a block's own code (e.g. Block.spawnDestroyParticles,
  // called from playerWillDestroy when the LOCAL player predicts breaking a block) plays that
  // event's sound/particles - it never goes through a network packet at all (see RecordingManager's
  // onLocalLevelEvent for the full explanation), unlike a real server-driven ClientboundLevelEventPacket
  // (which arrives via the 3-arg levelEvent(int, BlockPos, int) overload instead, so is unaffected here).
  @Inject(
    method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
    at = @At("HEAD")
  )
  private void recordingmod$onLevelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
    RecordingManager.INSTANCE.onLocalLevelEvent(player, type, pos, data);
  }
}
