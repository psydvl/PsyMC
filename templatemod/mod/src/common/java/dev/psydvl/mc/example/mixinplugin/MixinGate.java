package dev.psydvl.mc.example.mixinplugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The releases of each mixin that does not apply on the whole pin line. mixinCheck calls {@link
 * #allow} with each release (gradle.properties: mixin_gate_class). This class must not use Mixin or
 * the loaders: mixinCheck loads it from the jar without them.
 */
public final class MixinGate {
  // Simple mixin class name to the first release where it applies. Other mixins apply always.
  private static final Map<String, String> FIRST_RELEASE = Map.of("GatedTitleScreenMixin", "1.21");

  private static String current;

  private MixinGate() {}

  /** True when the mixin applies on the release. A null release (not known) allows every mixin. */
  public static boolean allow(String mixinClass, String release) {
    String first = FIRST_RELEASE.get(mixinClass.substring(mixinClass.lastIndexOf('.') + 1));
    return first == null || release == null || compare(release, first) >= 0;
  }

  /** The release of the running game, from version.json in the game jar. Null when not found. */
  public static synchronized String currentRelease() {
    if (current == null) {
      current = readRelease();
    }
    return current;
  }

  private static String readRelease() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    try (InputStream in =
        (loader != null ? loader : MixinGate.class.getClassLoader())
            .getResourceAsStream("version.json")) {
      if (in == null) {
        return null;
      }
      Matcher id =
          Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
              .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      return id.find() ? id.group(1) : null;
    } catch (IOException e) {
      return null;
    }
  }

  // Compare release ids such as 1.21.4 and 26.2 part by part. A missing part counts as 0.
  static int compare(String left, String right) {
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? parse(a[i]) : 0;
      int y = i < b.length ? parse(b[i]) : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return 0;
  }

  private static int parse(String part) {
    try {
      return Integer.parseInt(part);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
