package me.aris.recordingmod

import io.netty.buffer.Unpooled
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.Tag
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.io.File

// Ports the legacy 1.12.2 mod's "Generate Markers" button: read back a recording, purely offline
// (no world/playback involved), and drop a marker at every Hypixel SkyBlock Dungeons moment worth
// jumping back to later - a death, a notable item drop, or a dungeon run starting/finishing/failing.
//
// The legacy version found these by indexing into raw, formatting-code-laden strings at hardcoded
// offsets (e.g. a lore line's 5th character, or a drop tooltip's whole JSON blob by hand). That was
// already fragile in 2021 and even more likely to silently break now. This reads the same
// underlying data through the modern packet/NBT API instead (a real ItemStack from the hover
// event, decoded display-name/lore Components) so a shift in incidental spacing/padding on
// Hypixel's end doesn't throw the detection off - only the actual rarity/name text matters.
object MarkerGenerator {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/markers")

  // Adapted from the legacy killRegex (which matched on "§r"-delimited formatting boundaries) to
  // match plain text instead, since Component.getString() strips styling rather than baking in
  // legacy formatting characters.
  private val killRegex = Regex("""^(.+?) was slain by (.+?)(?: using .+)?\.?$""")

  private val rarityRegex = Regex(
    """(§[0-9a-fk-or])?(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|SUPREME|ADMIN)"""
  )

  private val tierByColorCode = mapOf('e' to "t5", 'd' to "t4", 'b' to "t3", 'a' to "t2", 'f' to "t1")

  // Runs generate() over every saved recording, mirroring the legacy button which scanned all of
  // them in one go. Returns the total number of markers created.
  fun generateForAllRecordings(): Int {
    val files = RecordingConfig.recordingsDir.listFiles { f -> f.extension == "rec" } ?: emptyArray()
    var total = 0
    for (file in files) {
      try {
        total += generate(file)
      } catch (e: Exception) {
        LOGGER.warn("Failed to generate markers for {}", file, e)
      }
    }
    return total
  }

  fun generate(recordingFile: File): Int {
    val recordingBaseName = recordingFile.nameWithoutExtension
    val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(recordingFile.readBytes()))
    var tick = 0
    var created = 0
    var lastSubtitle: String? = null
    var currentDungeon: String? = null

    fun mark(name: String) {
      MarkerManager.save(name, recordingBaseName, tick)
      created++
    }

    while (buf.isReadable) {
      when (val id = buf.readVarInt()) {
        RecordingFormat.TICK_END -> tick++
        RecordingFormat.PLAYER_SNAPSHOT -> skipPlayerSnapshot(buf)
        RecordingFormat.BLOCK_CHANGE -> {
          buf.readBlockPos(); buf.readVarInt()
        }
        RecordingFormat.SWING -> buf.readVarInt()
        RecordingFormat.BLOCK_BREAK_PROGRESS -> {
          buf.readVarInt(); buf.readBlockPos(); buf.readVarInt()
        }
        RecordingFormat.MINING_PARTICLE -> {
          buf.readBlockPos(); buf.readVarInt()
        }
        RecordingFormat.LOCAL_LEVEL_EVENT -> {
          buf.readVarInt(); buf.readBlockPos(); buf.readVarInt()
        }
        RecordingFormat.LOCAL_PLAY_SOUND -> {
          buf.readBlockPos(); buf.readResourceLocation(); buf.readEnum(SoundSource::class.java)
          buf.readFloat(); buf.readFloat()
        }
        else -> {
          if (id < 0) {
            LOGGER.warn("Unknown record type {} while generating markers for {}, stopping", id, recordingFile)
            return created
          }
          val length = buf.readVarInt()
          if (buf.readableBytes() < length) return created
          val packetBuf = FriendlyByteBuf(buf.readBytes(length))
          val packet = try {
            ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, packetBuf)
          } catch (e: Exception) {
            null
          }

          when (packet) {
            is ClientboundSystemChatPacket -> handleChat(packet.content(), ::mark)
            is ClientboundSetSubtitleTextPacket -> lastSubtitle = packet.text.string
            is ClientboundSetTitleTextPacket -> {
              val text = packet.text.string
              when {
                text.contains("Dungeon Complete!") -> mark("DFinish ${lastSubtitle.orEmpty()}".trim())
                text.contains("Dungeon Failed") -> mark("DFail ${currentDungeon.orEmpty()}".trim())
                text.isNotBlank() && !text.contains("Loading Shard") &&
                  lastSubtitle?.contains("Objective") == true -> {
                  currentDungeon = text
                  mark("DStart $text")
                }
              }
            }
            else -> {}
          }
        }
      }
    }
    return created
  }

  private fun skipPlayerSnapshot(buf: FriendlyByteBuf) {
    buf.readDouble(); buf.readDouble(); buf.readDouble()
    buf.readFloat(); buf.readFloat()
    buf.readDouble(); buf.readDouble(); buf.readDouble()
    buf.readBoolean(); buf.readVarInt()
  }

  private fun handleChat(content: Component, mark: (String) -> Unit) {
    val plain = content.string
    if (plain.contains("dropped:")) {
      val infos = mutableListOf<HoverEvent.ItemStackInfo>()
      collectShowItemInfos(content, infos)
      infos.forEach { info ->
        buildDropMarkerName(info.itemStack)?.let(mark)
      }
      return
    }

    killRegex.find(plain)?.let { match ->
      val killed = match.groupValues[1].trim()
      val killer = match.groupValues[2].trim()
      if (killed.isNotEmpty() && killer.isNotEmpty()) {
        mark("$killer x $killed")
      }
    }
  }

  private fun collectShowItemInfos(component: Component, out: MutableList<HoverEvent.ItemStackInfo>) {
    val hover = component.style.hoverEvent
    if (hover != null && hover.action === HoverEvent.Action.SHOW_ITEM) {
      hover.getValue(HoverEvent.Action.SHOW_ITEM)?.let { out.add(it) }
    }
    component.siblings.forEach { collectShowItemInfos(it, out) }
  }

  // Hypixel bakes legacy "§"-formatted text directly into item NBT (display.Name/Lore are raw
  // JSON text components whose *content* already contains the § color codes), so decoding them
  // and reading the resulting string back out preserves those codes rather than stripping them.
  private fun decodeNbtText(raw: String): String? = try {
    Component.Serializer.fromJson(raw)?.string
  } catch (e: Exception) {
    raw
  }

  private fun buildDropMarkerName(stack: ItemStack): String? {
    if (stack.item === Items.IRON_NUGGET || stack.item === Items.FILLED_MAP) return null
    val tag = stack.tag ?: return null
    val display = tag.getCompound("display")
    if (!display.contains("Name")) return null

    val path = BuiltInRegistries.ITEM.getKey(stack.item).path
    val type = when (path) {
      "bow" -> "bow"
      "shield" -> "shield"
      else -> path.split("_").getOrNull(1) ?: path
    }

    var rarity = "UNK"
    var tierCode: Char? = null
    if (display.contains("Lore", Tag.TAG_LIST.toInt())) {
      val lore = display.getList("Lore", Tag.TAG_STRING.toInt())
      for (i in lore.size - 1 downTo 0) {
        val line = decodeNbtText(lore.getString(i)) ?: continue
        val match = rarityRegex.find(line) ?: continue
        rarity = match.groupValues[2]
        tierCode = match.groupValues[1].getOrNull(1)
        break
      }
    }
    val tier = tierCode?.let { tierByColorCode[it] } ?: "UNK"

    return "drop_${tier}_${rarity.lowercase().replace(" ", "_")}_$type"
  }
}
