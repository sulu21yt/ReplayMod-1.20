package me.aris.recordingmod.mixins;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Exposes Player's private DATA_PLAYER_MODE_CUSTOMISATION accessor so PlaybackManager can restore
// which skin layers (jacket, sleeves, pants legs, hat) render - see RecordingFormat.LOCAL_SKIN_
// CUSTOMIZATION for why this needs its own capture: there is no public setter on Player at all
// (only ServerPlayer, handling a real client's ServerboundClientInformationPacket, ever writes it),
// and it's only ever sent to the server once (on join, or when the player changes the setting) -
// same "sent once near join" story as login/respawn/spawn-position/tags (points 8-9).
@Mixin(Player.class)
public interface PlayerModelCustomisationAccessorMixin {
  @Accessor("DATA_PLAYER_MODE_CUSTOMISATION")
  static EntityDataAccessor<Byte> recordingmod$dataPlayerModeCustomisation() {
    throw new AssertionError();
  }
}
