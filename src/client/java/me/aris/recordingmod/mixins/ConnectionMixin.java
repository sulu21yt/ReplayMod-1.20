package me.aris.recordingmod.mixins;

import io.netty.channel.ChannelHandlerContext;
import me.aris.recordingmod.RecordingManager;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
  @Inject(method = "channelRead0", at = @At("HEAD"))
  private void recordingmod$onPacketReceived(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
    RecordingManager.INSTANCE.onPacketReceived((Connection) (Object) this, packet);
  }
}
