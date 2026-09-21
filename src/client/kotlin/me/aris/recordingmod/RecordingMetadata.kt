package me.aris.recordingmod

import com.google.gson.Gson
import java.io.File

// Sidecar files for a recording (<base>.rec -> <base>.json / <base>.png). The packet-stream
// format itself has no header/index, so there's no way to answer "how long is this recording"
// without scanning the whole file - totalTicks is captured once, at record-stop time (see
// RecordingManager.stopInternal), and read back here by anything that needs a duration (the
// playback timeline, eventually a recordings-list duration column).
object RecordingMetadata {
  private val gson = Gson()
  private data class Data(val totalTicks: Int)

  fun metadataFile(recordingFile: File): File =
    File(recordingFile.parentFile, "${recordingFile.nameWithoutExtension}.json")

  fun thumbnailFile(recordingFile: File): File =
    File(recordingFile.parentFile, "${recordingFile.nameWithoutExtension}.png")

  fun save(recordingFile: File, totalTicks: Int) {
    runCatching { metadataFile(recordingFile).writeText(gson.toJson(Data(totalTicks))) }
  }

  // ponytail: no scan-the-file fallback for recordings made before this feature (or where the
  // save above failed) - callers just treat a null total as "unknown length" and degrade gracefully.
  fun readTotalTicks(recordingFile: File): Int? =
    runCatching { gson.fromJson(metadataFile(recordingFile).readText(), Data::class.java).totalTicks }.getOrNull()
}
