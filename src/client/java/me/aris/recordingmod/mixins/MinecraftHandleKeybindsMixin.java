package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During playback nobody is actually holding down attack/use/movement keys - it's just a replay.
// Without this, Minecraft.handleKeybinds() reads the real, live mouse/keyboard every tick and
// "corrects" the replayed state to match it (e.g. releasing a bow draw the instant it notices the
// right mouse button isn't physically held, since a ClientboundSetEntityDataPacket had just set
// isUsingItem=true a moment before). This is the same reason the original 1.12.2 mod explicitly
// disabled normal keybind handling while replaying.
//
// Cancelling the whole method also silently ate the perspective-toggle keybind (F5) along with
// everything else, since vanilla handles it in the same method (Minecraft.handleKeybinds, right
// at the top) - there was no way to switch to third person while watching a replay. Fixed by
// replicating just that one keybind's own handling (copied from vanilla's handleKeybinds) before
// cancelling the rest, rather than letting the real method run at all (which would still risk
// re-triggering the original bow-draw-class bug for every other key it checks).
@Mixin(Minecraft.class)
public abstract class MinecraftHandleKeybindsMixin {
  @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
  private void recordingmod$skipDuringPlayback(CallbackInfo ci) {
    if (PlaybackManager.INSTANCE.getActive()) {
      Minecraft mc = (Minecraft) (Object) this;
      while (mc.options.keyTogglePerspective.consumeClick()) {
        CameraType previous = mc.options.getCameraType();
        mc.options.setCameraType(previous.cycle());
        if (previous.isFirstPerson() != mc.options.getCameraType().isFirstPerson()) {
          mc.gameRenderer.checkEntityPostEffect(mc.options.getCameraType().isFirstPerson() ? mc.getCameraEntity() : null);
        }
        mc.levelRenderer.needsUpdate();
      }
      ci.cancel();
    }
  }
}
