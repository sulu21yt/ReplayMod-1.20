package me.aris.recordingmod.mixins;

import me.aris.recordingmod.PlaybackManager;
import me.aris.recordingmod.ReplayScreenOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A replayed ClientboundOpenScreenPacket (chest, furnace, ...) or horse-inventory packet would
// otherwise open a real, interactive screen mid-replay - grabbing the mouse and blocking the
// playback controls - and a replayed close packet would close whatever screen the viewer had open
// (e.g. the timeline). Both are redirected to ReplayScreenOverlay, which just draws the screen.
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {
  @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
  private void recordingmod$redirectReplayScreens(Screen screen, CallbackInfo ci) {
    Object packet = PlaybackManager.INSTANCE.getPacketInFlight();
    if (packet == null) return;
    if (screen instanceof AbstractContainerScreen<?> containerScreen) {
      ReplayScreenOverlay.INSTANCE.captureContainerScreen(containerScreen);
      ci.cancel();
    } else if (screen == null && packet instanceof ClientboundContainerClosePacket) {
      ReplayScreenOverlay.INSTANCE.onContainerClosed();
      ci.cancel();
    }
  }
}
