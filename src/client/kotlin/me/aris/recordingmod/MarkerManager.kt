package me.aris.recordingmod

import java.io.File

// Markers let you bookmark a moment while recording live (see MarkMomentScreen) and jump straight
// to it later from MarkersScreen, without having to scrub through the whole recording manually.
// A marker is just a file "markers/<name>-<recordingBaseName>" whose content is the tick number -
// same on-disk format the legacy 1.12.2 mod used, so old marker files still work.
object MarkerManager {
  private val dir = File("markers")

  data class Marker(val name: String, val recordingBaseName: String, val tick: Int, val file: File)

  fun list(): List<Marker> {
    return dir.listFiles { f -> f.isFile }
      ?.sortedByDescending { it.lastModified() }
      ?.mapNotNull { file ->
        val tick = file.readText().trim().toIntOrNull() ?: return@mapNotNull null
        val fileName = file.name
        val name = fileName.substringBeforeLast('-')
        val recordingBaseName = fileName.substringAfterLast('-')
        if (name == fileName || recordingBaseName.isEmpty()) return@mapNotNull null
        Marker(name, recordingBaseName, tick, file)
      }
      ?: emptyList()
  }

  // Sanitizes the marker name and de-duplicates against existing files, mirroring the legacy
  // mod's behavior exactly so old and new marker files stay compatible with each other.
  fun save(name: String, recordingBaseName: String, tick: Int) {
    dir.mkdirs()
    var safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    while (File(dir, "$safeName-$recordingBaseName").exists()) {
      safeName += "_"
    }
    File(dir, "$safeName-$recordingBaseName").writeText(tick.toString())
  }
}
