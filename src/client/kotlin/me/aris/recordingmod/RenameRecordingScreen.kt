package me.aris.recordingmod

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.File

// Opened from RecordingsScreen's "Rename" button. Renames the .rec file and, if present, its
// sidecar metadata/thumbnail (see RecordingMetadata) so they keep pointing at the same recording
// under its new name.
//
// ponytail: doesn't migrate markers (see MarkerManager.kt) that reference the old recordingBaseName
// in their filename - they'll silently stop resolving to this recording after a rename. Add a
// rename pass over markers/ if that turns out to matter in practice.
class RenameRecordingScreen(private val recordingFile: File, private val parent: Screen?) :
  Screen(Component.literal("Rename Recording")) {
  private lateinit var nameField: EditBox

  override fun init() {
    nameField = EditBox(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20, Component.literal("Recording name"))
    nameField.setMaxLength(128)
    nameField.value = recordingFile.nameWithoutExtension
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
    if (newName.isEmpty() || newName == recordingFile.nameWithoutExtension) {
      onClose()
      return
    }

    val dir = recordingFile.parentFile
    val newRecordingFile = File(dir, "$newName.rec")
    if (newRecordingFile.exists()) {
      this.minecraft?.player?.displayClientMessage(Component.literal("A recording named \"$newName\" already exists"), false)
      return
    }

    recordingFile.renameTo(newRecordingFile)
    RecordingMetadata.metadataFile(recordingFile).let { if (it.exists()) it.renameTo(RecordingMetadata.metadataFile(newRecordingFile)) }
    RecordingMetadata.thumbnailFile(recordingFile).let { if (it.exists()) it.renameTo(RecordingMetadata.thumbnailFile(newRecordingFile)) }

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
