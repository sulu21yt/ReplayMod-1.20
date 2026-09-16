package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
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
@Mixin(Minecraft.class)
public abstract class MinecraftHandleKeybindsMixin {
  @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
  private void recordingmod$skipDuringPlayback(CallbackInfo ci) {
    if (PlaybackManager.INSTANCE.getActive()) {
      ci.cancel();
    }
  }
}
