package me.aris.recordingmod.mixins;

import me.aris.recordingmod.RecordingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Level.playSound(Player, BlockPos, ...) - e.g. called by BlockItem.place() for the block-place
// sound - is, on the client, a pure local-prediction call: ClientLevel.playSeededSound only ever
// plays it when the given player is Minecraft.getInstance().player, and it never goes out or comes
// back as a network packet either way (see RecordingManager.onLocalPlaySound). Targeting the
// abstract Level class (rather than ClientLevel) also fires this on a real ServerLevel in
// singleplayer, since client and integrated server share one classloader - harmless, since
// RecordingManager checks isClientSide()/identity before doing anything with it.
@Mixin(Level.class)
public abstract class LevelPlaySoundMixin {
  @Inject(
    method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
    at = @At("HEAD")
  )
  private void recordingmod$onPlaySound(
    Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci
  ) {
    RecordingManager.INSTANCE.onLocalPlaySound((Level) (Object) this, player, pos, sound, source, volume, pitch);
  }
}
