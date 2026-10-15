package dev.psydvl.mc.smoke.audit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

// Load each target class of the mod mixin configs.
// Mixin applies the mixins when a class loads. An apply error goes to the game output.
// The system property mixinAudit.configs names the configs, separated by commas.
public final class MixinAudit {
  private MixinAudit() {}

  public static synchronized void run(ClassLoader loader) {
    Set<String> wanted =
        Arrays.stream(System.getProperty("mixinAudit.configs", "").split(","))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toSet());
    int configs = 0;
    int loaded = 0;
    int failed = 0;
    try {
      List<IMixinConfig> all = activeConfigs();
      for (IMixinConfig config : all) {
        if (!wanted.contains(fileName(config.getName()))) {
          continue;
        }
        configs++;
        for (String target : new TreeSet<>(config.getTargets())) {
          String name = target.replace('/', '.');
          try {
            // Do not run static initializers. The load alone applies the mixins.
            Class.forName(name, false, loader);
            loaded++;
          } catch (Throwable error) {
            failed++;
            System.out.println(
                "MIXIN-AUDIT FAIL " + config.getName() + " " + name + ": " + describe(error));
          }
        }
      }
      if (configs == 0) {
        List<String> names = new ArrayList<>();
        for (IMixinConfig config : all) {
          names.add(config.getName());
        }
        System.out.println(
            "MIXIN-AUDIT FAIL audit: no config of " + wanted + " in the active configs " + names);
      }
    } catch (Throwable error) {
      failed++;
      System.out.println("MIXIN-AUDIT FAIL audit: " + describe(error));
    }
    System.out.println(
        "MIXIN-AUDIT DONE configs=" + configs + " loaded=" + loaded + " failed=" + failed);
    System.out.flush();
  }

  // Mixin removes a config from Mixins.getConfigs when it selects it.
  // The processor of the active transformer keeps the selected configs.
  private static List<IMixinConfig> activeConfigs() throws ReflectiveOperationException {
    Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
    if (transformer == null) {
      throw new IllegalStateException("Mixin has no active transformer");
    }
    Object processor = fieldValue(transformer, "MixinProcessor", null);
    Object raw = fieldValue(processor, null, "configs");
    List<IMixinConfig> result = new ArrayList<>();
    for (Object item : (List<?>) raw) {
      result.add((IMixinConfig) item);
    }
    return result;
  }

  private static Object fieldValue(Object owner, String typeSuffix, String name)
      throws ReflectiveOperationException {
    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        boolean typeMatches = typeSuffix == null || field.getType().getName().endsWith(typeSuffix);
        boolean nameMatches = name == null || field.getName().equals(name);
        if (typeMatches && nameMatches) {
          field.setAccessible(true);
          return field.get(owner);
        }
      }
    }
    throw new NoSuchFieldException(
        owner.getClass().getName() + " has no field " + (name != null ? name : typeSuffix));
  }

  // Some loaders name a config "#modid:file". Fabric and NeoForge use the file name only.
  private static String fileName(String configName) {
    return configName.substring(configName.lastIndexOf(':') + 1);
  }

  private static String describe(Throwable error) {
    StringBuilder text = new StringBuilder(String.valueOf(error));
    Throwable cause = error.getCause();
    while (cause != null && cause != error) {
      text.append(" <- ").append(cause);
      error = cause;
      cause = cause.getCause();
    }
    return text.toString();
  }
}
