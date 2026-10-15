package dev.psydvl.mc.example.nil;

import dev.psydvl.mc.example.ExampleMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nilloader.api.ClassTransformer;
import nilloader.api.ModRemapper;
import nilloader.api.NilLogger;

/** NilLoader entry. NilLoader calls it in premain, before the game classes load. */
public final class ExampleModNil implements Runnable {
  private static final NilLogger LOG = NilLogger.get(ExampleMod.MOD_ID);

  @Override
  public void run() {
    selectMappings();
    // Do not load game classes here. Register the patches only.
    ClassTransformer.register(new BootstrapPatch());
    ClassTransformer.register(new TitleScreenPatch());
    ClassTransformer.register(new DedicatedServerPatch());
  }

  // The jar has mapping sets for the obfuscated releases of the pin line. Select the set for the
  // names of this game. A named release has no sets: the mod code uses its names already.
  private static void selectMappings() {
    String id;
    if (onClasspath("net/fabricmc/loader/impl/launch/knot/Knot.class")) {
      id = "intermediary";
    } else if (onClasspath("cpw/mods/bootstraplauncher/BootstrapLauncher.class")) {
      id = "mojmap";
    } else {
      id = gameRelease();
    }
    if (id == null) {
      return;
    }
    try {
      ModRemapper.setTargetMapping(id);
      LOG.info("Mappings: {}", id);
    } catch (NoSuchElementException e) {
      LOG.info("No mappings {} in the jar. The mod uses its own names.", id);
    }
  }

  // The release id from version.json of the vanilla client jar.
  private static String gameRelease() {
    try (InputStream in = ClassLoader.getSystemResourceAsStream("version.json")) {
      if (in == null) {
        return null;
      }
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
      return id.find() ? id.group(1) : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean onClasspath(String resource) {
    return ClassLoader.getSystemResource(resource) != null;
  }
}
