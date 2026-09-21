package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

// Opened from MarkersScreen's "Rename" button - the only way to give a marker a real name, since
// marking a moment live (see RecordingModClient.markMoment) no longer asks for one up front.
class RenameMarkerScreen(private val marker: MarkerManager.Marker, private val parent: Screen?) :
  Screen(Component.literal("Rename Marker")) {
  private lateinit var nameField: EditBox

  override fun init() {
    nameField = EditBox(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20, Component.literal("Marker name"))
    nameField.setMaxLength(128)
    nameField.value = marker.name
    nameField.setFocused(true)
    addRenderableWidget(nameField)
    setInitialFocus(nameField)

    addRenderableWidget(
      Button.builder(Component.literal("Save")) { save() }
        .bounds(this.width / 2 - 50, this.height / 2, 100, 20)
        .build()
    )
  }

  private fun save() {
    val newName = nameField.value.trim()
    if (newName.isNotEmpty() && newName != marker.name) {
      MarkerManager.rename(marker, newName)
    }
    onClose()
  }

  override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      save()
      return true
    }
    return super.keyPressed(keyCode, scanCode, modifiers)
  }

  override fun onClose() {
    this.minecraft?.setScreen(parent)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF)
  }

  override fun isPauseScreen() = false
}
