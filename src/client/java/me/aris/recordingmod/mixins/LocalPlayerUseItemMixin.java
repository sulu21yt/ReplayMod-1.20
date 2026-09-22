package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// LocalPlayer.isUsingItem() (overridden from LivingEntity) reads a purely local `startedUsingItem`
// field, set only by LocalPlayer.startUsingItem()/stopUsingItem() - never by the synced entity-data
// flag LivingEntity itself uses. This is client prediction, same category as swing/block-break: the
// server never echoes it back as a packet the owning player would receive. Confirmed via
// ChunkMap.TrackedEntity.updatePlayer, which explicitly skips adding a player's own connection to
// its own entity's tracked viewers ("if (serverPlayer != this.entity)") - so a ClientboundSetEntityDataPacket
// for the recording player's own using-item flag is never sent to that same player, on singleplayer
// OR a real server. Without this mixin, the recording player's own bow/food/shield/trident draw
// animations never play during playback: isUsingItem() stays false the whole time, so
// LivingEntity.tick()'s updatingUsingItem() (which drives useItemRemaining, i.e. draw progress) never
// runs at all - the item renders statically instead of progressively charging.
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerUseItemMixin {
  @Inject(method = "startUsingItem(Lnet/minecraft/world/InteractionHand;)V", at = @At("TAIL"))
  private void recordingmod$onStartUsingItem(InteractionHand hand, CallbackInfo ci) {
    RecordingManager.INSTANCE.onLocalStartUsingItem(hand);
  }

  @Inject(method = "stopUsingItem()V", at = @At("HEAD"))
  private void recordingmod$onStopUsingItem(CallbackInfo ci) {
    RecordingManager.INSTANCE.onLocalStopUsingItem();
  }
}
