package me.aris.recordingmod.mixins;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// LocalPlayer.isCrouching() overrides the base Entity method to read this private field directly
// instead of the entity's Pose - see PlaybackManager.applySneakState for why playback has to set
// it explicitly rather than relying on Player.updatePlayerPose()'s Pose.CROUCHING to be enough.
@Mixin(LocalPlayer.class)
public interface LocalPlayerCrouchingAccessorMixin {
  @Accessor("crouching")
  void recordingmod$setCrouching(boolean value);
}
