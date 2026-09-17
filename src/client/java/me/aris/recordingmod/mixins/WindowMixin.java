package me.aris.recordingmod.mixins;

import com.mojang.blaze3d.platform.Window;
import me.aris.recordingmod.VideoExporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Window.updateDisplay() runs once per real rendered frame, right before the buffer swap
// (RenderSystem.flipFrame) - the latest frame is still sitting in the default framebuffer at
// this point, which is exactly what VideoExporter needs to glReadPixels from.
@Mixin(Window.class)
public abstract class WindowMixin {
  @Inject(method = "updateDisplay", at = @At("HEAD"))
  private void recordingmod$onUpdateDisplay(CallbackInfo ci) {
    VideoExporter.INSTANCE.onFrameReady();
  }
}
