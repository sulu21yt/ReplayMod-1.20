package me.aris.recordingmod.mixins;

import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Timer.class)
public interface TimerAccessorMixin {
  @Mutable
  @Accessor("msPerTick")
  void recordingmod$setMsPerTick(float msPerTick);

  @Accessor("msPerTick")
  float recordingmod$getMsPerTick();
}
