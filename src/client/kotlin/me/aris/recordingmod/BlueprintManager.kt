package me.aris.recordingmod

import java.io.File

// A "blueprint" marks out a tick range of a recording worth rendering to video, optionally with
// slow-motion regions inside it - same on-disk format the legacy 1.12.2 mod used
// (blueprints/<start>..<end>-<recordingBaseName>.bp, one "<start>..<end>,<multiplier>" line per
// slow-motion region) so old blueprint files still work. Splitting rendering into "render a cheap
// proxy first, review it, then commit to a full-quality render" (see BlueprintRenderer) is the
// whole reason blueprints exist as their own concept instead of just rendering markers directly.
object BlueprintManager {
  private val dir = File("blueprints")

  data class SloMoRegion(val range: IntRange, val slowMultiplier: Int)

  data class Blueprint(
    val startTick: Int,
    val endTick: Int,
    val recordingBaseName: String,
    val sloMoRegions: List<SloMoRegion>,
    val file: File
  ) {
    // Shared by proxy/final output file naming (see BlueprintRenderer/VideoExporter) - matches
    // this blueprint's own file name (minus extension) so a rendered video is easy to trace back
    // to the blueprint that produced it.
    val baseName: String get() = "$startTick..$endTick-$recordingBaseName"
  }

  fun list(): List<Blueprint> {
    return dir.listFiles { f -> f.extension == "bp" }
      ?.sortedByDescending { it.lastModified() }
      ?.mapNotNull { parse(it) }
      ?: emptyList()
  }

  private fun parse(file: File): Blueprint? {
    val name = file.nameWithoutExtension
    val dashIndex = name.indexOf('-')
    if (dashIndex < 0) return null
    val rangePart = name.substring(0, dashIndex)
    val recordingBaseName = name.substring(dashIndex + 1)
    if (recordingBaseName.isEmpty()) return null

    val rangeSplit = rangePart.split("..")
    if (rangeSplit.size != 2) return null
    val start = rangeSplit[0].toIntOrNull() ?: return null
    val end = rangeSplit[1].toIntOrNull() ?: return null

    val regions = file.readLines().mapNotNull { line -> parseSloMoLine(line) }
    return Blueprint(start, end, recordingBaseName, regions, file)
  }

  private fun parseSloMoLine(line: String): SloMoRegion? {
    if (line.isBlank()) return null
    val parts = line.trim().split(",")
    if (parts.size != 2) return null
    val range = parts[0].split("..")
    if (range.size != 2) return null
    val start = range[0].toIntOrNull() ?: return null
    val end = range[1].toIntOrNull() ?: return null
    val multiplier = parts[1].toIntOrNull() ?: return null
    return SloMoRegion(start..end, multiplier)
  }

  fun hasProxy(blueprint: Blueprint): Boolean = File("proxies", "${blueprint.baseName}.mp4").exists()

  fun hasFinal(blueprint: Blueprint): Boolean =
    File(RecordingConfig.finalRenderPath, "${blueprint.baseName}.mp4").exists()

  // A blueprint spans from 20 seconds before a marked moment to 5 seconds after it (20 ticks/sec),
  // matching the legacy mod's window exactly.
  private const val TICKS_PER_SECOND = 20
  private const val SECONDS_BEFORE = 20
  private const val SECONDS_AFTER = 5

  // Returns how many new blueprint files were created (existing ones for the same marker are left
  // untouched, same as the legacy button).
  fun generateFromMarkers(): Int {
    dir.mkdirs()
    var created = 0
    for (marker in MarkerManager.list()) {
      val start = (marker.tick - SECONDS_BEFORE * TICKS_PER_SECOND).coerceAtLeast(0)
      val end = marker.tick + SECONDS_AFTER * TICKS_PER_SECOND
      val file = File(dir, "$start..$end-${marker.recordingBaseName}.bp")
      if (!file.exists()) {
        file.createNewFile()
        created++
      }
    }
    return created
  }
}
