package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
import me.aris.recordingmod.VideoExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// During playback the mouse wheel would otherwise just cycle the hotbar slot, same as in a real
// world - repurposed here to scrub instead, same 5s-per-notch granularity as the skip_back/
// forward_5s keybinds, since scrolling through a recording is a much more natural gesture than
// mashing a keybind repeatedly. Skipped during export for the same reason skipSeconds is: an
// export drives PlaybackManager's tick count itself and isn't real playback.
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
  private static final int TICKS_PER_SECOND = 20;
  private static final int SECONDS_PER_NOTCH = 5;

  @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
  private void recordingmod$scrubDuringPlayback(long window, double xOffset, double yOffset, CallbackInfo ci) {
    if (!PlaybackManager.INSTANCE.getActive() || VideoExporter.INSTANCE.getActive() || yOffset == 0) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.screen != null) return;

    int targetTick = PlaybackManager.INSTANCE.getCurrentTick() + (int) Math.signum(yOffset) * SECONDS_PER_NOTCH * TICKS_PER_SECOND;
    PlaybackManager.INSTANCE.seekTo(targetTick);
    // A seek that clamps to tick 0 restarts playback but skips fastForwardTo's loop body entirely
    // (0 < 0 is false), so mc.player may not exist yet the instant seekTo returns - same reason
    // skipSeconds (RecordingModClient.kt) uses a null-safe mc.player?.displayClientMessage.
    if (PlaybackManager.INSTANCE.getActive() && mc.player != null) {
      mc.player.displayClientMessage(
        Component.literal("Skipped to " + (PlaybackManager.INSTANCE.getCurrentTick() / TICKS_PER_SECOND) + "s"), true
      );
    }
    ci.cancel();
  }
}
