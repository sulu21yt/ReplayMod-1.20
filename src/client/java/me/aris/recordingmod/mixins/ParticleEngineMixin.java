package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
  @Inject(method = "crack", at = @At("HEAD"))
  private void recordingmod$onCrack(BlockPos pos, Direction direction, CallbackInfo ci) {
    RecordingManager.INSTANCE.onMiningParticle(pos, direction);
  }
}
