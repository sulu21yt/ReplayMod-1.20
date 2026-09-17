package me.aris.recordingmod.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Exposes Entity's protected movement-sound methods so PlaybackManager can trigger them directly.
// Footstep/swim/splash sounds are normally only produced as a side effect of Entity.move()'s real
// physics/collision pass, which playback never calls (the recorded player position is applied by
// direct teleport instead) - see PlaybackManager's own movement-sound tracking for why this is needed.
@Mixin(Entity.class)
public interface EntityMovementSoundInvokerMixin {
  @Invoker("playStepSound")
  void recordingmod$playStepSound(BlockPos pos, BlockState state);

  @Invoker("waterSwimSound")
  void recordingmod$waterSwimSound();

  @Invoker("doWaterSplashEffect")
  void recordingmod$doWaterSplashEffect();
}
