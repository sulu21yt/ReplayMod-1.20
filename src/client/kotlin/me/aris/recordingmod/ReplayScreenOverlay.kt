package me.aris.recordingmod

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.util.Mth

// Shows the recorded player's open inventory/container screen during playback, drawn over the HUD
// instead of set as the real mc.screen - a real screen would grab the mouse and keyboard (and
// block the playback controls) the moment a replayed chest opened. Container screens opened by
// replayed packets are intercepted here (see MinecraftSetScreenMixin); the player's own inventory
// (E) never touches the network, so its open/closed state, the mouse position and the cursor item
// come from the recording's LOCAL_SCREEN_STATE records instead.
object ReplayScreenOverlay {
  private var kind = RecordingFormat.SCREEN_NONE
  // Mouse position at the previous and latest recorded tick - interpolated per frame (like the
  // camera) so the cursor and hover highlight glide instead of jumping 20 times a second.
  private var prevMouseX = 0f
  private var prevMouseY = 0f
  private var mouseX = 0f
  private var mouseY = 0f

  val showing: Boolean get() = PlaybackManager.active && currentScreen() != null

  // The last container screen a replayed packet tried to open - shown while kind is CONTAINER.
  private var containerScreen: AbstractContainerScreen<*>? = null
  private var inventoryScreen: InventoryScreen? = null

  // Screen.init has to run once per screen and again on every GUI size change.
  private var initializedScreen: Screen? = null
  private var initializedWidth = 0
  private var initializedHeight = 0

  fun reset() {
    kind = RecordingFormat.SCREEN_NONE
    containerScreen = null
    inventoryScreen = null
    initializedScreen = null
  }

  fun captureContainerScreen(screen: AbstractContainerScreen<*>) {
    containerScreen = screen
  }

  fun onContainerClosed() {
    containerScreen = null
  }

  fun applyState(buf: FriendlyByteBuf) {
    val newKind = buf.readByte().toInt()
    val player = Minecraft.getInstance().player
    if (newKind == RecordingFormat.SCREEN_NONE) {
      // Closing a container is only ever sent client -> server, never echoed back - so this is
      // the only place playback learns the recorded player closed it.
      if (kind == RecordingFormat.SCREEN_CONTAINER && player != null) {
        player.containerMenu = player.inventoryMenu
        containerScreen = null
      }
      player?.containerMenu?.carried = net.minecraft.world.item.ItemStack.EMPTY
    } else {
      val x = buf.readFloat()
      val y = buf.readFloat()
      // Just opened: start from where the mouse actually is, not wherever it was last time.
      prevMouseX = if (kind == RecordingFormat.SCREEN_NONE) x else mouseX
      prevMouseY = if (kind == RecordingFormat.SCREEN_NONE) y else mouseY
      mouseX = x
      mouseY = y
      val carried = buf.readItem()
      player?.containerMenu?.carried = carried
    }
    kind = newKind
  }

  private fun currentScreen(): Screen? {
    val player = Minecraft.getInstance().player ?: return null
    return when (kind) {
      RecordingFormat.SCREEN_INVENTORY -> {
        val existing = inventoryScreen
        // A backward seek rebuilds the world with a brand-new LocalPlayer.
        if (existing != null && existing.menu === player.inventoryMenu) existing
        else InventoryScreen(player).also { inventoryScreen = it }
      }
      RecordingFormat.SCREEN_CONTAINER -> containerScreen
      else -> null
    }
  }

  fun render(guiGraphics: GuiGraphics) {
    if (!PlaybackManager.active) return
    val mc = Minecraft.getInstance()
    val screen = currentScreen() ?: return
    val width = guiGraphics.guiWidth()
    val height = guiGraphics.guiHeight()
    if (screen !== initializedScreen || width != initializedWidth || height != initializedHeight) {
      screen.init(mc, width, height)
      initializedScreen = screen
      initializedWidth = width
      initializedHeight = height
    }

    val f = PlaybackManager.renderPartialTick
    val x = (Mth.lerp(f, prevMouseX, mouseX) * width).toInt()
    val y = (Mth.lerp(f, prevMouseY, mouseY) * height).toInt()
    // Same sequence GameRenderer.render uses for a real screen: finish the HUD, clear depth so
    // nothing from the HUD (e.g. hotbar items) pokes through, then renderWithTooltip.
    guiGraphics.flush()
    RenderSystem.clear(256, Minecraft.ON_OSX)
    // No cursor is drawn - the mouse position still drives the hover highlight, tooltips and
    // where the cursor item is held, which is what actually shows where the player was pointing.
    screen.renderWithTooltip(guiGraphics, x, y, mc.deltaFrameTime)
  }
}
