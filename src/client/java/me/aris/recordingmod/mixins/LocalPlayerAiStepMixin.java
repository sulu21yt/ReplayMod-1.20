package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During playback, the fake replay player is a real LocalPlayer, whose aiStep() reads real, live
// keyboard/mouse input every tick (this.input.tick(...)) to drive real movement physics (gravity,
// friction, collision via travel()) - the same "reads live input, corrects/interferes with
// replayed state" class of bug MinecraftHandleKeybindsMixin already fixes for attack/use, just for
// movement instead. PlaybackManager already overrides position directly every render frame (see
// onRenderFrame), so this real physics simulation running underneath is pure interference -
// fighting our own position/velocity every single tick. This was the actual cause of a persistent,
// tick-rate-frequency "vibration"/choppiness specifically visible in third person during playback
// (invisible in first person, and never happens during live play) that survived several other
// targeted fixes - confirmed only after ruling those out.
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerAiStepMixin {
  @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
  private void recordingmod$skipDuringPlayback(CallbackInfo ci) {
    if (PlaybackManager.INSTANCE.getActive()) {
      ci.cancel();
    }
  }
}
