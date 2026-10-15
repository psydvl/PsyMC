package dev.psydvl.mc.example.nil;

import dev.psydvl.mc.example.ExampleMod;
import net.minecraft.server.MinecraftServer;

/**
 * Static methods that the patches call. The patches use Object so that no game name is in a
 * descriptor.
 */
public final class NilHooks {
  private static boolean initialized;

  private NilHooks() {}

  public static void onBootstrap() {
    // Bootstrap can run more than once. Set up the mod once.
    if (initialized) {
      return;
    }
    initialized = true;
    ExampleMod.init();
  }

  public static void onTitleScreen() {
    ExampleMod.onTitleScreen();
  }

  // The dedicated server that starts now. DedicatedServerPatch keeps it at the start of initServer.
  private static Object server;

  public static void rememberServer(Object value) {
    server = value;
  }

  public static void onServerStarted() {
    ExampleMod.onServerStarted((MinecraftServer) server);
  }
}
