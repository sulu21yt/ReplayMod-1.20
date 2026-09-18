package me.aris.recordingmod

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

// Drives a whole batch of blueprint renders back-to-back, the way the legacy mod's "Render
// Blueprints"/"Render Blueprint Proxies" buttons did: kick off one render on the main thread,
// block a background thread until VideoExporter finishes it, then move on to the next blueprint.
// The main thread itself is never blocked, so the game keeps ticking/rendering normally the whole
// time - which is what lets VideoExporter's real-time capture (see its own top comment) progress
// at all.
//
// Eligibility differs from the legacy version, which used the same filter for both buttons
// (requiring an existing proxy AND no existing final render) - that would have made "Render
// Blueprint Proxies" a no-op, since a blueprint with a proxy already doesn't need another one.
// Here, proxies render blueprints that don't have one yet, and final renders only ones that do
// (and don't have a final render yet) - i.e. review the cheap proxy first, then commit.
object BlueprintRenderer {
  private val LOGGER = LoggerFactory.getLogger("recordingmod/blueprints")

  @Volatile
  var running = false
    private set

  fun renderAll(proxy: Boolean) {
    val mc = Minecraft.getInstance()
    if (running) {
      mc.player?.displayClientMessage(Component.literal("Already rendering blueprints"), false)
      return
    }

    val blueprints = BlueprintManager.list().filter { blueprint ->
      if (proxy) {
        !BlueprintManager.hasProxy(blueprint)
      } else {
        BlueprintManager.hasProxy(blueprint) && !BlueprintManager.hasFinal(blueprint)
      }
    }

    if (blueprints.isEmpty()) {
      mc.player?.displayClientMessage(
        Component.literal(
          if (proxy) "No blueprints need a proxy render"
          else "No blueprints are ready for a final render (render a proxy first)"
        ),
        false
      )
      return
    }

    running = true
    mc.player?.displayClientMessage(
      Component.literal("Rendering ${blueprints.size} blueprint(s)${if (proxy) " (proxy)" else ""}..."), false
    )

    Thread {
      var rendered = 0
      try {
        for (blueprint in blueprints) {
          if (renderOneBlocking(mc, blueprint, proxy)) rendered++
        }
      } finally {
        running = false
        val finalRendered = rendered
        mc.execute {
          mc.player?.displayClientMessage(
            Component.literal("Finished rendering $finalRendered/${blueprints.size} blueprint(s)"), false
          )
        }
      }
    }.start()
  }

  private fun renderOneBlocking(mc: Minecraft, blueprint: BlueprintManager.Blueprint, proxy: Boolean): Boolean {
    val startedFuture = CompletableFuture<Boolean>()
    mc.execute {
      startedFuture.complete(VideoExporter.startBlueprintRender(blueprint, proxy))
    }

    val started = try {
      startedFuture.get()
    } catch (e: Exception) {
      LOGGER.warn("Failed to start render for blueprint {}", blueprint.file.name, e)
      false
    }
    if (!started) return false

    // isBusy, not active: a requested window resize (see VideoExporter.requestStart) briefly
    // leaves active=false before the real capture starts, which would otherwise let this loop
    // exit immediately and race ahead to the next blueprint mid-resize.
    while (VideoExporter.isBusy) {
      Thread.sleep(50)
    }
    return true
  }
}
