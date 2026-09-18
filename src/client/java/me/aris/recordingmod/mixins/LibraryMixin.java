package me.aris.recordingmod.mixins;

import com.mojang.blaze3d.audio.Library;
import me.aris.recordingmod.AudioExporter;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

// Lets AudioExporter capture real game audio without any OS-level virtual audio cable: while
// AudioExporter.getWantsLoopback() is true, redirects the two OpenAL calls Library.init() makes to
// open its device and create its context - Library itself is otherwise untouched, so the rest of
// its device/channel-pool setup (which normally works the same for any valid ALC device) just
// keeps working against a loopback device instead of a real one. AudioExporter learns the loopback
// device's handle via onDeviceOpened() the moment it's created, since Library's own fields are
// private with no accessors.
@Mixin(Library.class)
public abstract class LibraryMixin {
  @Redirect(
    method = "tryOpenDevice",
    at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcOpenDevice(Ljava/lang/CharSequence;)J")
  )
  private static long recordingmod$openDevice(CharSequence deviceSpecifier) {
    if (AudioExporter.INSTANCE.getWantsLoopback()) {
      long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
      AudioExporter.INSTANCE.onDeviceOpened(device);
      return device;
    }
    return ALC10.alcOpenDevice(deviceSpecifier);
  }

  @Redirect(
    method = "init",
    at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J")
  )
  private long recordingmod$createContext(long device, IntBuffer attribs) {
    if (AudioExporter.INSTANCE.getWantsLoopback()) {
      return ALC10.alcCreateContext(device, AudioExporter.INSTANCE.loopbackContextAttribs());
    }
    return ALC10.alcCreateContext(device, attribs);
  }
}
