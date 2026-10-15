package dev.psydvl.mc.example;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared code of the mod. The loader entry classes call it. */
public final class ExampleMod {
  public static final String MOD_ID = "examplemod";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private ExampleMod() {}

  // The loader entry calls this once during mod setup.
  public static void init() {
    LOGGER.info("[{}] loaded. Probe id: {}", MOD_ID, ModIds.id("probe"));
  }

  // serverSmoke waits for this line on the dedicated server.
  public static void onServerStarted(MinecraftServer server) {
    LOGGER.info("[{}] server started on {}", MOD_ID, server.getServerVersion());
  }

  // The title screen mixin calls this. A play run shows the line in the log.
  // The game calls TitleScreen.init again after each window resize. Thus the line can show more
  // than once.
  public static void onTitleScreen() {
    LOGGER.info("[{}] title screen init", MOD_ID);
  }
}
