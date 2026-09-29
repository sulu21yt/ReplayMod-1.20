package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
import me.aris.recordingmod.RecordingManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
  @Inject(method = "render", at = @At("HEAD"))
  private void recordingmod$onRenderFrame(float partialTick, long finishTimeNano, boolean shouldRenderLevel, CallbackInfo ci) {
    RecordingManager.INSTANCE.onRenderFrame(partialTick);
    PlaybackManager.INSTANCE.onRenderFrame(partialTick);
  }
}
