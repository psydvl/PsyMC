package psymc.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// NeoForge creates LWJGL modules after the JVM starts.
// A startup --add-reads flag cannot see those modules.
// Add the read edge when an LWJGL class loads.
public final class ModuleReadsAgent implements ClassFileTransformer {
  private static Instrumentation instrumentation;
  private static final Set<String> opened = new HashSet<>();

  public static void premain(String args, Instrumentation value) {
    instrumentation = value;
    System.err.println("ModuleReadsAgent: premain");
    value.addTransformer(new ModuleReadsAgent(), true);
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    note(null, loader, className);
    return null;
  }

  @Override
  public byte[] transform(
      Module module,
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    note(module, loader, className);
    return null;
  }

  private static void note(Module module, ClassLoader loader, String className) {
    if (className == null || !className.startsWith("org/lwjgl")) {
      return;
    }
    if (module == null || !module.isNamed()) {
      return;
    }
    Module api = findApi(module, loader);
    if (api == null || api == module || !opened.add(module.getName())) {
      return;
    }
    try {
      instrumentation.redefineModule(module, Set.of(api), Map.of(), Map.of(), Set.of(), Map.of());
      System.err.println("ModuleReadsAgent: " + module.getName() + " reads " + api.getName());
    } catch (Throwable error) {
      System.err.println("ModuleReadsAgent: " + module.getName() + " " + error);
    }
  }

  private static Module findApi(Module caller, ClassLoader loader) {
    Set<ModuleLayer> seen = new HashSet<>();
    ArrayDeque<ModuleLayer> pending = new ArrayDeque<>();
    if (caller.getLayer() != null) {
      pending.add(caller.getLayer());
    }
    pending.add(ModuleLayer.boot());
    while (!pending.isEmpty()) {
      ModuleLayer layer = pending.removeFirst();
      if (!seen.add(layer)) {
        continue;
      }
      for (Module candidate : layer.modules()) {
        if (candidate.getPackages().contains("io.github.headlesshq.headlessmc.lwjgl.api")) {
          return candidate;
        }
      }
      pending.addAll(layer.parents());
    }
    if (loader == null) {
      return null;
    }
    try {
      Class<?> api =
          Class.forName("io.github.headlesshq.headlessmc.lwjgl.api.RedirectionApi", false, loader);
      return api.getModule();
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }
}
