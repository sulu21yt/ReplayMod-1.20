package me.aris.recordingmod.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import me.aris.recordingmod.PlaybackManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TEMPORARY - diagnosing the "items/hotbar missing in first person after recording in third
// person" report. Logs the exact guard state GameRenderer.renderItemInHand checks (the same
// conditions that gate ItemInHandRenderer.renderHandsWithItems), throttled to roughly once per
// second, only while playback is active. Remove once root-caused.
@Mixin(GameRenderer.class)
public abstract class DebugRenderItemInHandMixin {
  private static final Logger LOGGER = LoggerFactory.getLogger("recordingmod/debug");

  @Shadow
  private Minecraft minecraft;

  private int recordingmod$debugFrameCounter = 0;

  @Inject(method = "renderItemInHand", at = @At("HEAD"))
  private void recordingmod$logGuardState(PoseStack poseStack, Camera camera, float partialTick, CallbackInfo ci) {
    if (!PlaybackManager.INSTANCE.getActive()) return;
    if (recordingmod$debugFrameCounter++ % 60 != 0) return;

    boolean sleeping = this.minecraft.getCameraEntity() instanceof LivingEntity
      && ((LivingEntity) this.minecraft.getCameraEntity()).isSleeping();

    LOGGER.info(
      "renderItemInHand guard state: firstPerson={} sleeping={} hideGui={} playerMode={} player={} cameraEntity={}",
      this.minecraft.options.getCameraType().isFirstPerson(),
      sleeping,
      this.minecraft.options.hideGui,
      this.minecraft.gameMode != null ? this.minecraft.gameMode.getPlayerMode() : "null-gameMode",
      this.minecraft.player,
      this.minecraft.getCameraEntity()
    );
  }
}
