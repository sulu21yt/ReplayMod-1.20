package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
}
