package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackControls;
import me.aris.recordingmod.RecordingModClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hardcoded playback controls (see PlaybackControls) and the live marker key - read here, per key
// event, rather than as registered KeyMappings, so none of them show up in (or can be clobbered
// from) the Controls menu. Our keys are swallowed during playback so e.g. Space doesn't also jump.
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void recordingmod$onKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
    if (window != Minecraft.getInstance().getWindow().getWindow()) return;
    if (PlaybackControls.INSTANCE.handleKey(key, action)) {
      ci.cancel();
      return;
    }
    RecordingModClient.INSTANCE.onLiveKeyPress(key, action);
  }
}
