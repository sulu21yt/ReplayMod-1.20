package me.aris.recordingmod.mixins;

import me.aris.recordingmod.VideoExporter;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// During an export, vanilla's own timer is driven by the export clock instead of real time (see
// VideoExporter.advanceExportTimer), so the client tick loop - and with it PlaybackManager.tick()
// and every vanilla-ticked entity/animation - runs in lockstep with the output frames, and the
// partialTick every renderer reads is the real fraction between the two playback ticks being shown.
@Mixin(Timer.class)
public abstract class TimerMixin {
  @Shadow public float partialTick;
  @Shadow private long lastMs;

  @Inject(method = "advanceTime", at = @At("HEAD"), cancellable = true)
  private void recordingmod$exportClock(long ms, CallbackInfoReturnable<Integer> cir) {
    if (!VideoExporter.INSTANCE.getActive()) return;
    // Keep lastMs current so real-time ticking resumes cleanly once the export ends.
    this.lastMs = ms;
    int ticks = VideoExporter.INSTANCE.advanceExportTimer();
    this.partialTick = VideoExporter.INSTANCE.getExportPartialTick();
    cir.setReturnValue(ticks);
  }
}
