package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackControls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hold-K slow motion and Space pause during playback (see PlaybackControls). Slow motion slows the
// game timer itself, like the legacy mod did, so every entity/animation/particle - not just the
// replay feed - moves slower with smooth partialTick interpolation in between. Pause reuses
// vanilla's own singleplayer pause flag: runTick recomputes it every frame (always false outside
// singleplayer), so it's forced back on at the end of each frame while we want it.
@Mixin(Minecraft.class)
public abstract class MinecraftRunTickMixin {
  @Shadow private volatile boolean pause;
  @Shadow private float pausePartialTick;
  @Shadow @Final private Timer timer;

  @Inject(method = "runTick", at = @At("HEAD"))
  private void recordingmod$applyPlaybackSpeed(boolean tick, CallbackInfo ci) {
    TimerAccessorMixin accessor = (TimerAccessorMixin) this.timer;
    float target = PlaybackControls.INSTANCE.msPerTick();
    if (accessor.recordingmod$getMsPerTick() != target) {
      accessor.recordingmod$setMsPerTick(target);
    }
  }

  @Inject(method = "runTick", at = @At("TAIL"))
  private void recordingmod$applyPlaybackPause(boolean tick, CallbackInfo ci) {
    if (PlaybackControls.INSTANCE.shouldFreezeGame() && !this.pause) {
      this.pausePartialTick = this.timer.partialTick;
      this.pause = true;
    }
  }
}
