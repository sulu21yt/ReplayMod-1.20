package me.aris.recordingmod

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.sign

// A simple list of past recordings so you can watch one back later, not just the most recent one.
// Scrollable with the mouse wheel once there are more than fit on screen.
class RecordingsScreen(private val parent: Screen?) : Screen(Component.literal("Recordings")) {
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  private var files: List<File> = emptyList()
  private var scrollOffset = 0
  private var maxVisible = 1

  // Textures for the currently visible page's thumbnails only - re-registered (and the previous
  // batch released) every rebuildList(), so we never accumulate GPU textures for rows scrolled
  // past or recordings that no longer exist. Width/height are the actual screenshot's pixel size
  // (needed for blit's UV mapping) - not the small size it's drawn at.
  private data class Thumbnail(val location: ResourceLocation, val width: Int, val height: Int)
  private var thumbnails: List<Thumbnail?> = emptyList()

  private val thumbnailWidth = 32
  private val thumbnailHeight = 18
  private val thumbnailGap = 4
  private val buttonWidth = 320
  private val playButtonWidth = 180
  private val exportButtonWidth = 60
  private val renameButtonWidth = buttonWidth - playButtonWidth - exportButtonWidth - 8
  private val buttonHeight = 20
  private val spacing = 4
  private val startY = 40

  override fun init() {
    files = RecordingConfig.recordingsDir.listFiles { f -> f.extension == "rec" }
      ?.sortedByDescending { it.lastModified() }
      ?: emptyList()
    maxVisible = ((this.height - startY - 40) / (buttonHeight + spacing)).coerceAtLeast(1)
    scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())

    rebuildList()
  }

  override fun removed() {
    releaseThumbnails()
  }

  private fun releaseThumbnails() {
    thumbnails.forEach { it?.let { t -> this.minecraft?.textureManager?.release(t.location) } }
    thumbnails = emptyList()
  }

  private fun loadThumbnail(index: Int, file: File): Thumbnail? {
    val thumbFile = RecordingMetadata.thumbnailFile(file)
    if (!thumbFile.exists()) return null
    return runCatching {
      val image = thumbFile.inputStream().use { NativeImage.read(it) }
      val location = ResourceLocation("recordingmod", "recording_thumbnail_$index")
      this.minecraft?.textureManager?.register(location, DynamicTexture(image))
      Thumbnail(location, image.width, image.height)
    }.getOrNull()
  }

  private fun maxScrollOffset() = (files.size - maxVisible).coerceAtLeast(0)

  private fun rowX() = this.width / 2 - (thumbnailWidth + thumbnailGap + buttonWidth) / 2
  private fun buttonsX() = rowX() + thumbnailWidth + thumbnailGap

  private fun rebuildList() {
    clearWidgets()
    releaseThumbnails()

    if (files.isEmpty()) {
      addRenderableWidget(
        Button.builder(Component.literal("No recordings found")) {}
          .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
          .build()
      ).active = false
    }

    val visible = files.drop(scrollOffset).take(maxVisible)
    thumbnails = visible.mapIndexed { index, file -> loadThumbnail(index, file) }

    visible.forEachIndexed { index, file ->
      val label = "${file.nameWithoutExtension} (${dateFormat.format(Date(file.lastModified()))})"
      val bx = buttonsX()
      val rowY = startY + index * (buttonHeight + spacing)
      addRenderableWidget(
        Button.builder(Component.literal(label)) {
          PlaybackManager.start(file)
          this.minecraft?.setScreen(null)
        }.bounds(bx, rowY, playButtonWidth, buttonHeight).build()
      )
      addRenderableWidget(
        Button.builder(Component.literal("Export")) {
          val output = File(RecordingConfig.finalRenderPath, "${file.nameWithoutExtension}.mp4")
          // Only close the screen if the export actually started - if ffmpeg failed to launch,
          // PlaybackManager never started either, and closing anyway would leave the player
          // staring at a blank world with no screen at all.
          if (VideoExporter.start(file, output)) {
            this.minecraft?.setScreen(null)
          }
        }.bounds(bx + playButtonWidth + 4, rowY, exportButtonWidth, buttonHeight).build()
      )
      addRenderableWidget(
        Button.builder(Component.literal("Rename")) {
          this.minecraft?.setScreen(RenameRecordingScreen(file, this))
        }.bounds(bx + playButtonWidth + 4 + exportButtonWidth + 4, rowY, renameButtonWidth, buttonHeight).build()
      )
    }

    addRenderableWidget(
      Button.builder(Component.literal("Cancel")) { onClose() }
        .bounds(this.width / 2 - 100, this.height - 30, 200, 20)
        .build()
    )
  }

  override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
    if (maxScrollOffset() == 0) return super.mouseScrolled(mouseX, mouseY, delta)
    val newOffset = (scrollOffset - delta.sign.toInt()).coerceIn(0, maxScrollOffset())
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset
      rebuildList()
    }
    return true
  }

  override fun onClose() {
    this.minecraft?.setScreen(parent)
  }

  override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    this.renderBackground(guiGraphics)
    super.render(guiGraphics, mouseX, mouseY, partialTick)
    guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF)

    val rx = rowX()
    thumbnails.forEachIndexed { index, thumbnail ->
      if (thumbnail == null) return@forEachIndexed
      val rowY = startY + index * (buttonHeight + spacing) + (buttonHeight - thumbnailHeight) / 2

      // blit has no scale-to-fit overload, only 1:1 crop - draw at full source size inside a
      // scaled pose instead, so an arbitrary-resolution screenshot shrinks to the thumbnail box.
      guiGraphics.pose().pushPose()
      guiGraphics.pose().translate(rx.toDouble(), rowY.toDouble(), 0.0)
      guiGraphics.pose().scale(thumbnailWidth.toFloat() / thumbnail.width, thumbnailHeight.toFloat() / thumbnail.height, 1f)
      guiGraphics.blit(thumbnail.location, 0, 0, 0f, 0f, thumbnail.width, thumbnail.height, thumbnail.width, thumbnail.height)
      guiGraphics.pose().popPose()
    }

    if (files.size > maxVisible) {
      val first = scrollOffset + 1
      val last = (scrollOffset + maxVisible).coerceAtMost(files.size)
      guiGraphics.drawCenteredString(
        this.font, "$first-$last of ${files.size} (scroll for more)", this.width / 2, 27, 0xA0A0A0
      )
    }
  }
}
