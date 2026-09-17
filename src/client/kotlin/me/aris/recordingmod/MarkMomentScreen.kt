package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

// Opened by the "mark moment" keybind while recording live, so you can bookmark a cool moment
// (by name) and jump straight back to it later from MarkersScreen, instead of scrubbing through
// the whole recording. Only usable while actively recording, since a marker needs to know both
// which file and which tick it's pointing at (see RecordingManager.currentFile/currentTick).
class MarkMomentScreen(private val recordingFile: java.io.File, private val tick: Int) :
  Screen(Component.literal("Mark Moment")) {
  private lateinit var nameField: EditBox

  override fun init() {
    nameField = EditBox(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20, Component.literal("Marker name"))
    nameField.setMaxLength(128)
    nameField.setFocused(true)
    addRenderableWidget(nameField)
    setInitialFocus(nameField)

    addRenderableWidget(
      Button.builder(Component.literal("Save Marker")) { save() }
        .bounds(this.width / 2 - 50, this.height / 2, 100, 20)
        .build()
    )
  }

  private fun save() {
    val name = nameField.value.ifBlank { "marker" }
    MarkerManager.save(name, recordingFile.nameWithoutExtension, tick)
    this.minecraft?.setScreen(null)
    this.minecraft?.player?.displayClientMessage(Component.literal("Saved marker \"$name\""), false)
  }

  override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      save()
      return true
    }
    return super.keyPressed(keyCode, scanCode, modifiers)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF)
  }

  override fun isPauseScreen() = false
}
