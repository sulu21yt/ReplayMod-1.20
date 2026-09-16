package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
  @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
  private void recordingmod$onSwing(InteractionHand hand, boolean broadcast, CallbackInfo ci) {
    RecordingManager.INSTANCE.onSwing((LivingEntity) (Object) this, hand);
  }
}
