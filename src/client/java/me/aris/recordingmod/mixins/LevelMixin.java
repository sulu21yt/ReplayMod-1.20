package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
  @Inject(
    method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
    at = @At("RETURN")
  )
  private void recordingmod$onSetBlock(
    BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir
  ) {
    if (cir.getReturnValueZ()) {
      RecordingManager.INSTANCE.onBlockChanged((Level) (Object) this, pos, state);
    }
  }
}
