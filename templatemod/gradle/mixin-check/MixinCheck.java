import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

// Compare the mixins of one pin jar with the Minecraft jar of each release.
// This tool reads bytecode only. It does not load Mixin or the game.
// Arguments: pin jar, report file, pin id, loader, gate class, plugin package, expected file,
// then release=jar pairs.
// The gate class has a static allow(String mixinClass, String release). "-" means no gate: all
// mixins are on.
// The plugin package holds mixins that a config plugin adds from getMixins. "-" means none.
// The expected file lists known misses. "-" means none. One entry on each line:
// <loader or *> | <mixin class, dotted> | <text in the issue> | <reason>
// Lines that start with # are comments. The tool ignores blank lines.
// The exit code is 0 when each miss is expected. The exit code is 3 when a miss is not expected.
public final class MixinCheck {
  private static final String[] GAME_PACKAGES = {
    "net/minecraft/", "com/mojang/blaze3d/", "com/mojang/math/", "com/mojang/realmsclient/",
  };
  private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
  private static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
  private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
  private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
  private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
  private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
  private static final Set<String> INJECTORS =
      Set.of(
          "Lorg/spongepowered/asm/mixin/injection/Inject;",
          "Lorg/spongepowered/asm/mixin/injection/Redirect;",
          "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
          "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
          "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
          "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
          "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
          "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
          "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
          "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
          "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
          "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
          "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
  private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
  private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
  private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
  private static final String WRAP_OPERATION =
      "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
  private static final String MODIFY_EXPRESSION_VALUE =
      "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
  private static final String CALLBACK_INFO =
      "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
  private static final String CALLBACK_INFO_RETURNABLE =
      "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
  private static final String OPERATION =
      "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
  private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
  private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/";
  private static final String SUGAR_REF = "Lcom/llamalad7/mixinextras/sugar/ref/";
  // A class name in a descriptor or a signature.
  private static final Pattern DESC_CLASS = Pattern.compile("L([^;<>()\\[\\s:]+)[;<]");
  // A class name with dots or slashes, for example in @Mixin(targets).
  private static final Pattern PLAIN_CLASS =
      Pattern.compile("[A-Za-z_$][\\w$]*([./][A-Za-z_$][\\w$]*)+");

  record MixinClass(String name, String config, ClassNode node) {}

  record Selector(String owner, String name, String desc) {}

  // One instruction that an @At finds in one target method.
  record Match(MethodNode hit, AbstractInsnNode insn, String kind) {}

  // One line of the expected file.
  static final class Expect {
    final int line;
    final String loader;
    final String mixin;
    final String text;
    final String reason;
    boolean used;

    Expect(int line, String loader, String mixin, String text, String reason) {
      this.line = line;
      this.loader = loader;
      this.mixin = mixin;
      this.text = text;
      this.reason = reason;
    }

    String source() {
      return loader + " | " + mixin + " | " + text;
    }
  }

  static final class Game {
    final String version;
    final ZipFile zip;
    final Map<String, Optional<ClassNode>> cache = new HashMap<>();

    Game(String version, Path jar) throws IOException {
      this.version = version;
      this.zip = new ZipFile(jar.toFile());
    }

    ClassNode get(String internalName) {
      return cache.computeIfAbsent(internalName, this::read).orElse(null);
    }

    private Optional<ClassNode> read(String internalName) {
      ZipEntry entry = zip.getEntry(internalName + ".class");
      if (entry == null) {
        return Optional.empty();
      }
      try (InputStream in = zip.getInputStream(entry)) {
        ClassNode node = new ClassNode();
        new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
        return Optional.of(node);
      } catch (IOException error) {
        return Optional.empty();
      }
    }
  }

  // One problem text for one mixin. The set holds the releases that show it.
  private final Map<String, TreeSet<String>> issues = new TreeMap<>();
  // The releases where the gate lets each mixin on.
  private final Map<String, TreeSet<String>> activeOn = new TreeMap<>();
  private final Map<String, int[]> perRelease = new TreeMap<>();
  private final List<String> absent = new ArrayList<>();
  private final Set<String> external = new TreeSet<>();
  private final Set<String> pseudo = new TreeSet<>();
  // Compile stubs in game packages. At run time a mixin applies to the stub.
  private final Set<String> stubs = new TreeSet<>();
  // The class names that each mixin refers to.
  private final Map<String, Set<String>> references = new HashMap<>();
  // The entries of the expected file for this loader.
  private final List<Expect> expects = new ArrayList<>();
  // The expected entry for each issue key. Issues without an entry are not expected.
  private final Map<String, Expect> expectedBy = new HashMap<>();

  public static void main(String[] args) throws Exception {
    if (args.length < 8) {
      throw new IllegalArgumentException(
          "Give the pin jar, report file, pin, loader, gate, plugin package, expected file, and"
              + " release=jar pairs.");
    }
    Path pinJar = Path.of(args[0]);
    Path report = Path.of(args[1]);
    String pin = args[2];
    String loader = args[3];
    String gateClass = args[4];
    String pluginPackage = args[5].equals("-") ? null : args[5].replace('.', '/') + "/";
    Path expectedFile = args[6].equals("-") ? null : Path.of(args[6]);
    Map<String, Path> games = new java.util.LinkedHashMap<>();
    List<String> missingJars = new ArrayList<>();
    for (int index = 7; index < args.length; index++) {
      int eq = args[index].indexOf('=');
      String version = args[index].substring(0, eq);
      String path = args[index].substring(eq + 1);
      if (path.isEmpty()) {
        missingJars.add(version);
      } else {
        games.put(version, Path.of(path));
      }
    }
    MixinCheck check = new MixinCheck();
    if (expectedFile != null) {
      check.readExpected(expectedFile, loader);
    }
    List<MixinClass> mixins = check.readMixins(pinJar, pluginPackage);
    Method gate = gateClass.equals("-") ? null : gateMethod(pinJar, gateClass);
    for (Map.Entry<String, Path> entry : games.entrySet()) {
      Game game = new Game(entry.getKey(), entry.getValue());
      try {
        check.checkRelease(game, mixins, gate);
      } finally {
        game.zip.close();
      }
    }
    check.classify();
    String text =
        check.report(pin, loader, pinJar, gateClass, expectedFile, games.keySet(), missingJars);
    Path parent = report.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(report, text, StandardCharsets.UTF_8);
    System.out.println(check.summary());
    if (check.unexpected() > 0) {
      System.exit(3);
    }
  }

  // Read the expected file. Keep the entries for this loader and for "*".
  private void readExpected(Path file, String loader) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int index = 0; index < lines.size(); index++) {
      // Remove a byte order mark. Some editors write one.
      String line = lines.get(index).replace("﻿", "").trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\|", 4);
      if (parts.length < 4) {
        throw new IllegalArgumentException(
            file + ":" + (index + 1) + ": give loader | mixin | text | reason.");
      }
      String entryLoader = parts[0].trim();
      if (!entryLoader.equals("*") && !entryLoader.equals(loader)) {
        continue;
      }
      String text = parts[2].trim();
      if (text.isEmpty()) {
        throw new IllegalArgumentException(file + ":" + (index + 1) + ": the text is empty.");
      }
      expects.add(new Expect(index + 1, entryLoader, parts[1].trim(), text, parts[3].trim()));
    }
  }

  // Find the expected entry for each issue. The first entry that matches wins.
  private void classify() {
    for (String key : issues.keySet()) {
      String mixin = key.substring(0, key.indexOf(" | "));
      String text = key.substring(key.indexOf(" | ") + 3);
      for (Expect expect : expects) {
        if (expect.mixin.equals(mixin) && text.contains(expect.text)) {
          expect.used = true;
          expectedBy.put(key, expect);
          break;
        }
      }
    }
  }

  private int unexpected() {
    return issues.size() - expectedBy.size() + absent.size();
  }

  private int stale() {
    int count = 0;
    for (Expect expect : expects) {
      if (!expect.used) {
        count++;
      }
    }
    return count;
  }

  private static Method gateMethod(Path pinJar, String gateClass) throws Exception {
    URLClassLoader loader =
        new URLClassLoader(
            new URL[] {pinJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    Class<?> gate = loader.loadClass(gateClass);
    return gate.getMethod("allow", String.class, String.class);
  }

  private List<MixinClass> readMixins(Path pinJar, String pluginPackage) throws IOException {
    Map<String, String> listed = new TreeMap<>();
    Map<String, ClassNode> found = new TreeMap<>();
    try (ZipFile zip = new ZipFile(pinJar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.contains("/") && name.endsWith(".mixins.json")) {
          String json =
              new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
          readConfig(name, json, listed);
        } else if (name.endsWith(".class")) {
          // Read the code too. The stub check scans the instructions of each mixin.
          ClassNode node = new ClassNode();
          try (InputStream in = zip.getInputStream(entry)) {
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
          }
          if (annotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN) != null) {
            found.put(node.name, node);
          } else if (gameClass(node.name)) {
            stubs.add(node.name);
          }
        }
      }
    }
    List<MixinClass> result = new ArrayList<>();
    listed.forEach(
        (name, config) -> {
          ClassNode node = found.get(name);
          if (node == null) {
            absent.add(config + ": " + name.replace('/', '.'));
          } else {
            result.add(new MixinClass(name, config, node));
          }
        });
    found.forEach(
        (name, node) -> {
          if (!listed.containsKey(name)
              && pluginPackage != null
              && name.startsWith(pluginPackage)) {
            result.add(new MixinClass(name, "plugin getMixins", node));
          }
        });
    return result;
  }

  private static void readConfig(String config, String json, Map<String, String> listed) {
    Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    if (!pkg.find()) {
      return;
    }
    String base = pkg.group(1).replace('.', '/') + "/";
    Matcher list =
        Pattern.compile("\"(mixins|client|server)\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
    while (list.find()) {
      Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(list.group(2));
      while (item.find()) {
        listed.put(base + item.group(1).replace('.', '/'), config);
      }
    }
  }

  private void checkRelease(Game game, List<MixinClass> mixins, Method gate) throws Exception {
    int active = 0;
    for (MixinClass mixin : mixins) {
      String dotted = mixin.name().replace('/', '.');
      if (gate != null && !(Boolean) gate.invoke(null, dotted, game.version)) {
        continue;
      }
      ClassNode node = mixin.node();
      if (annotation(node.visibleAnnotations, node.invisibleAnnotations, PSEUDO) != null) {
        pseudo.add(label(mixin));
        continue;
      }
      List<String> targets = targets(node);
      List<String> own = new ArrayList<>();
      for (String target : targets) {
        if (gameClass(target)) {
          own.add(target);
        }
      }
      if (own.isEmpty()) {
        external.add(label(mixin));
        continue;
      }
      active++;
      activeOn
          .computeIfAbsent(label(mixin), key -> new TreeSet<>(MixinCheck::compareVersions))
          .add(game.version);
      // A compile stub is only in the pin jar. On a release without the class, the mixin fails.
      Set<String> refs = references.computeIfAbsent(mixin.name(), key -> collectReferences(node));
      for (String ref : refs) {
        if (stubs.contains(ref) && !own.contains(ref) && game.get(ref) == null) {
          issue(
              mixin,
              "references compile stub " + ref.replace('/', '.') + ", absent on this release",
              game.version);
        }
      }
      for (String target : own) {
        ClassNode targetNode = game.get(target);
        String where = own.size() > 1 ? " in " + simple(target) : "";
        if (targetNode == null) {
          String stub = stubs.contains(target) ? ". The pin jar has a stub of it" : "";
          issue(
              mixin,
              "target class " + target.replace('/', '.') + " is missing" + stub,
              game.version);
          continue;
        }
        checkMembers(game, mixin, targetNode, where);
      }
    }
    perRelease.put(game.version, new int[] {active, countFor(game.version)});
  }

  private void checkMembers(Game game, MixinClass mixin, ClassNode target, String where) {
    ClassNode node = mixin.node();
    for (FieldNode field : node.fields) {
      AnnotationNode shadow =
          annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW);
      if (shadow == null) {
        continue;
      }
      Set<String> names = shadowNames(field.name, shadow);
      if (findField(game, target.name, names, field.desc, new TreeSet<>()) == 0) {
        issue(
            mixin,
            "@Shadow field " + field.name + " " + field.desc + where + " is missing",
            game.version);
      }
    }
    for (MethodNode method : node.methods) {
      AnnotationNode shadow =
          annotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW);
      if (shadow != null) {
        Set<String> names = shadowNames(method.name, shadow);
        if (findMethod(game, target.name, names, method.desc, new TreeSet<>()) == 0) {
          issue(
              mixin,
              "@Shadow method " + method.name + method.desc + where + " is missing",
              game.version);
        }
      }
      if (annotation(method.visibleAnnotations, method.invisibleAnnotations, OVERWRITE) != null
          && !declares(target, Set.of(method.name), method.desc)) {
        issue(
            mixin,
            "@Overwrite " + method.name + method.desc + where + " has no target",
            game.version);
      }
      AnnotationNode accessor =
          annotation(method.visibleAnnotations, method.invisibleAnnotations, ACCESSOR);
      if (accessor != null) {
        checkAccessor(game, mixin, target, method, accessor, where);
      }
      AnnotationNode invoker =
          annotation(method.visibleAnnotations, method.invisibleAnnotations, INVOKER);
      if (invoker != null) {
        checkInvoker(game, mixin, target, method, invoker, where);
      }
      for (AnnotationNode injector : injectors(method)) {
        checkInjector(game, mixin, target, method, injector, where);
      }
    }
  }

  private void checkAccessor(
      Game game,
      MixinClass mixin,
      ClassNode target,
      MethodNode method,
      AnnotationNode accessor,
      String where) {
    Set<String> names = new LinkedHashSet<>();
    String explicit = (String) value(accessor, "value");
    if (explicit != null && !explicit.isEmpty()) {
      names.add(explicit);
    } else {
      Matcher match = Pattern.compile("^(get|set|is)([A-Z].*)$").matcher(method.name);
      if (!match.matches()) {
        issue(mixin, "@Accessor " + method.name + where + " has no field name", game.version);
        return;
      }
      addInflections(names, match.group(2));
    }
    Type type = Type.getMethodType(method.desc);
    String fieldDesc =
        type.getArgumentTypes().length == 0
            ? type.getReturnType().getDescriptor()
            : type.getArgumentTypes()[0].getDescriptor();
    FieldNode byName = null;
    for (FieldNode field : target.fields) {
      if (names.contains(field.name)) {
        byName = field;
        if (field.desc.equals(fieldDesc)) {
          return;
        }
      }
    }
    if (byName == null) {
      issue(mixin, "@Accessor " + method.name + " finds no field " + names + where, game.version);
    } else {
      issue(
          mixin,
          "@Accessor "
              + method.name
              + " type "
              + fieldDesc
              + " differs from "
              + byName.desc
              + where,
          game.version);
    }
  }

  private void checkInvoker(
      Game game,
      MixinClass mixin,
      ClassNode target,
      MethodNode method,
      AnnotationNode invoker,
      String where) {
    Set<String> names = new LinkedHashSet<>();
    String explicit = (String) value(invoker, "value");
    boolean constructor = false;
    if (explicit != null && !explicit.isEmpty()) {
      names.add(explicit);
      constructor = explicit.equals("<init>");
    } else {
      Matcher match = Pattern.compile("^(call|invoke|new|create)([A-Z].*)$").matcher(method.name);
      if (!match.matches()) {
        issue(mixin, "@Invoker " + method.name + where + " has no method name", game.version);
        return;
      }
      addInflections(names, match.group(2));
      constructor = match.group(1).equals("new") || match.group(1).equals("create");
    }
    if (declares(target, names, method.desc)) {
      return;
    }
    if (constructor) {
      String args = method.desc.substring(0, method.desc.indexOf(')') + 1);
      if (declares(target, Set.of("<init>"), args + "V")) {
        return;
      }
    }
    issue(
        mixin,
        "@Invoker " + method.name + method.desc + " finds no method " + names + where,
        game.version);
  }

  private void checkInjector(
      Game game,
      MixinClass mixin,
      ClassNode target,
      MethodNode method,
      AnnotationNode injector,
      String where) {
    List<String> selectors = strings(value(injector, "method"));
    if (selectors.isEmpty()) {
      return;
    }
    String kind = simple(Type.getType(injector.desc).getInternalName());
    List<MethodNode> hits = new ArrayList<>();
    boolean checked = false;
    for (String raw : selectors) {
      Selector selector = parseSelector(raw);
      if (selector == null) {
        continue;
      }
      checked = true;
      if (selector.owner() != null && !selector.owner().equals(target.name)) {
        continue;
      }
      for (MethodNode candidate : target.methods) {
        if (selectorMatches(selector, candidate)) {
          hits.add(candidate);
        }
      }
    }
    if (!checked) {
      return;
    }
    String head = "@" + kind + " " + method.name + where;
    if (hits.isEmpty()) {
      issue(mixin, head + " finds no method " + selectors, game.version);
      return;
    }
    List<Match> matches = new ArrayList<>();
    for (AnnotationNode at : ats(value(injector, "at"))) {
      matches.addAll(checkAt(game, mixin, hits, at, head));
    }
    checkHandler(game, mixin, method, injector, hits, matches, head);
  }

  // Return the instructions that the @At finds. With an ordinal, return only that instruction.
  private List<Match> checkAt(
      Game game, MixinClass mixin, List<MethodNode> hits, AnnotationNode at, String head) {
    List<Match> result = new ArrayList<>();
    String kind = (String) value(at, "value");
    String target = (String) value(at, "target");
    if (kind == null || target == null || target.isEmpty()) {
      return result;
    }
    if (!kind.equals("INVOKE")
        && !kind.equals("INVOKE_ASSIGN")
        && !kind.equals("FIELD")
        && !kind.equals("NEW")) {
      return result;
    }
    Object ordinalValue = value(at, "ordinal");
    int ordinal = ordinalValue instanceof Integer number ? number : -1;
    Object opcodeValue = value(at, "opcode");
    int opcode = opcodeValue instanceof Integer number ? number : -1;
    int best = 0;
    for (MethodNode hit : hits) {
      List<AbstractInsnNode> found = matchAt(hit, kind, target, opcode);
      if (found == null) {
        // The tool cannot read this target form. Count it as found.
        best = Math.max(best, 1);
        continue;
      }
      best = Math.max(best, found.size());
      if (ordinal < 0) {
        for (AbstractInsnNode insn : found) {
          result.add(new Match(hit, insn, kind));
        }
      } else if (ordinal < found.size()) {
        result.add(new Match(hit, found.get(ordinal), kind));
      }
    }
    if (best == 0) {
      issue(mixin, head + " finds no @At " + kind + " " + target, game.version);
    } else if (ordinal >= 0 && best <= ordinal) {
      issue(
          mixin,
          head + " @At " + kind + " " + target + " has " + best + " matches, ordinal " + ordinal,
          game.version);
    }
    return result;
  }

  // Return null when the tool cannot read the target, for example a regex target.
  private static List<AbstractInsnNode> matchAt(
      MethodNode method, String kind, String target, int opcode) {
    List<AbstractInsnNode> result = new ArrayList<>();
    if (method.instructions == null) {
      return result;
    }
    if (kind.equals("NEW")) {
      String type = newType(target);
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof TypeInsnNode typeInsn
            && insn.getOpcode() == Opcodes.NEW
            && typeInsn.desc.equals(type)) {
          result.add(insn);
        }
      }
      return result;
    }
    if (kind.equals("FIELD")) {
      int colon = target.indexOf(':');
      String left = colon < 0 ? target : target.substring(0, colon);
      String desc = colon < 0 ? null : target.substring(colon + 1);
      String[] ownerName = splitOwner(left);
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof FieldInsnNode field
            && field.name.equals(ownerName[1])
            && (ownerName[0] == null || ownerName[0].equals(field.owner))
            && (desc == null || desc.equals(field.desc))
            && (opcode < 0 || opcode == insn.getOpcode())) {
          result.add(insn);
        }
      }
      return result;
    }
    Selector selector = parseSelector(target);
    if (selector == null) {
      return null;
    }
    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof MethodInsnNode call
          && call.name.equals(selector.name())
          && (selector.owner() == null || selector.owner().equals(call.owner))
          && (selector.desc() == null || selector.desc().equals(call.desc))) {
        result.add(insn);
      }
    }
    return result;
  }

  // Compare the handler signature with the target method and the matched instructions.
  private void checkHandler(
      Game game,
      MixinClass mixin,
      MethodNode handler,
      AnnotationNode injector,
      List<MethodNode> hits,
      List<Match> matches,
      String head) {
    Type[] params = Type.getArgumentTypes(handler.desc);
    Type result = Type.getReturnType(handler.desc);
    checkArgsOnly(game, mixin, handler, params, hits, head);
    switch (injector.desc) {
      case INJECT -> checkInject(game, mixin, handler, injector, params, hits, head);
      case MODIFY_EXPRESSION_VALUE -> {
        for (Match match : matches) {
          Type value = valueType(match);
          if (value == null) {
            continue;
          }
          if (params.length == 0 || !params[0].equals(value) || !result.equals(value)) {
            issue(
                mixin,
                head
                    + " takes "
                    + (params.length == 0 ? "nothing" : params[0].getDescriptor())
                    + " and returns "
                    + result.getDescriptor()
                    + ", but "
                    + describe(match.insn())
                    + " gives "
                    + value.getDescriptor(),
                game.version);
          }
        }
      }
      case MODIFY_ARG -> {
        Object indexValue = value(injector, "index");
        for (Match match : matches) {
          if (!match.kind().equals("INVOKE") || !(match.insn() instanceof MethodInsnNode call)) {
            continue;
          }
          Type[] callArgs = Type.getArgumentTypes(call.desc);
          boolean ok;
          if (indexValue instanceof Integer index) {
            ok =
                index >= 0
                    && index < callArgs.length
                    && result.equals(callArgs[index])
                    && (startsWith(params, callArgs)
                        || (params.length > 0 && params[0].equals(callArgs[index])));
          } else {
            ok =
                Arrays.asList(callArgs).contains(result)
                    && (startsWith(params, callArgs)
                        || (params.length > 0 && params[0].equals(result)));
          }
          if (!ok) {
            issue(
                mixin,
                head
                    + " ("
                    + join(params)
                    + ")"
                    + result.getDescriptor()
                    + (indexValue instanceof Integer index ? " index " + index : "")
                    + " does not fit the args of "
                    + describe(call),
                game.version);
          }
        }
      }
      case WRAP_OPERATION, REDIRECT -> {
        boolean wrap = injector.desc.equals(WRAP_OPERATION);
        for (Match match : matches) {
          if (!match.kind().equals("INVOKE") || !(match.insn() instanceof MethodInsnNode call)) {
            continue;
          }
          List<Type> expected = new ArrayList<>();
          if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            expected.add(Type.getObjectType(call.owner));
          }
          expected.addAll(Arrays.asList(Type.getArgumentTypes(call.desc)));
          if (wrap) {
            expected.add(Type.getType(OPERATION));
          }
          Type[] want = expected.toArray(new Type[0]);
          boolean ok = startsWith(params, want) && result.equals(Type.getReturnType(call.desc));
          if (ok) {
            Type[] targetArgs = Type.getArgumentTypes(match.hit().desc);
            for (int index = want.length; index < params.length; index++) {
              int at = index - want.length;
              boolean captured =
                  !wrap && at < targetArgs.length && params[index].equals(targetArgs[at]);
              if (!captured && !sugar(handler, index)) {
                ok = false;
              }
            }
          }
          if (!ok) {
            issue(
                mixin,
                head
                    + " ("
                    + join(params)
                    + ")"
                    + result.getDescriptor()
                    + " does not match "
                    + describe(call)
                    + ", want ("
                    + join(want)
                    + ")"
                    + Type.getReturnType(call.desc).getDescriptor(),
                game.version);
          }
        }
      }
      default -> {}
    }
  }

  // An @Inject handler takes a prefix of the target args, then CallbackInfo or
  // CallbackInfoReturnable, then only MixinExtras sugar params.
  private void checkInject(
      Game game,
      MixinClass mixin,
      MethodNode handler,
      AnnotationNode injector,
      Type[] params,
      List<MethodNode> hits,
      String head) {
    int callback = -1;
    for (int index = 0; index < params.length; index++) {
      String desc = params[index].getDescriptor();
      if (desc.equals(CALLBACK_INFO) || desc.equals(CALLBACK_INFO_RETURNABLE)) {
        callback = index;
        break;
      }
    }
    boolean locals = value(injector, "locals") != null;
    for (MethodNode hit : hits) {
      Type[] targetArgs = Type.getArgumentTypes(hit.desc);
      String want =
          Type.getReturnType(hit.desc).getSort() == Type.VOID
              ? CALLBACK_INFO
              : CALLBACK_INFO_RETURNABLE;
      boolean ok =
          callback >= 0
              && callback <= targetArgs.length
              && params[callback].getDescriptor().equals(want);
      for (int index = 0; ok && index < callback; index++) {
        ok = params[index].equals(targetArgs[index]);
      }
      for (int index = callback + 1; ok && !locals && index < params.length; index++) {
        ok = sugar(handler, index);
      }
      if (!ok) {
        issue(
            mixin,
            head + " args (" + join(params) + ") do not match target " + hit.name + hit.desc,
            game.version);
      }
    }
  }

  // A param with @Local(argsOnly = true) must be one of the target method args.
  private void checkArgsOnly(
      Game game,
      MixinClass mixin,
      MethodNode handler,
      Type[] params,
      List<MethodNode> hits,
      String head) {
    for (int index = 0; index < params.length; index++) {
      AnnotationNode local = paramAnnotation(handler, index, LOCAL);
      if (local == null || !Boolean.TRUE.equals(value(local, "argsOnly"))) {
        continue;
      }
      Type type = params[index];
      if (type.getDescriptor().startsWith(SUGAR_REF)) {
        // A reference wrapper hides the real type. Do not check it.
        continue;
      }
      Object slotValue = value(local, "index");
      Object ordinalValue = value(local, "ordinal");
      for (MethodNode hit : hits) {
        Type[] targetArgs = Type.getArgumentTypes(hit.desc);
        boolean ok = false;
        if (slotValue instanceof Integer slot) {
          int at = (hit.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
          for (Type arg : targetArgs) {
            if (at == slot) {
              ok = arg.equals(type);
              break;
            }
            at += arg.getSize();
          }
        } else {
          int count = 0;
          for (Type arg : targetArgs) {
            if (arg.equals(type)) {
              count++;
            }
          }
          ok = ordinalValue instanceof Integer ordinal ? count > ordinal : count > 0;
        }
        if (!ok) {
          String where =
              slotValue != null
                  ? " index " + slotValue
                  : ordinalValue != null ? " ordinal " + ordinalValue : "";
          issue(
              mixin,
              head
                  + " @Local(argsOnly) "
                  + type.getDescriptor()
                  + where
                  + " is not an arg of target "
                  + hit.name
                  + hit.desc,
              game.version);
        }
      }
    }
  }

  // The type of the value that a matched instruction gives.
  private static Type valueType(Match match) {
    if (match.kind().equals("INVOKE") && match.insn() instanceof MethodInsnNode call) {
      return Type.getReturnType(call.desc);
    }
    if (match.kind().equals("FIELD") && match.insn() instanceof FieldInsnNode field) {
      return Type.getType(field.desc);
    }
    return null;
  }

  private static String describe(AbstractInsnNode insn) {
    if (insn instanceof MethodInsnNode call) {
      return "L" + call.owner + ";" + call.name + call.desc;
    }
    if (insn instanceof FieldInsnNode field) {
      return "L" + field.owner + ";" + field.name + ":" + field.desc;
    }
    return "the instruction";
  }

  private static boolean startsWith(Type[] params, Type[] head) {
    if (params.length < head.length) {
      return false;
    }
    for (int index = 0; index < head.length; index++) {
      if (!params[index].equals(head[index])) {
        return false;
      }
    }
    return true;
  }

  private static String join(Type[] types) {
    StringBuilder out = new StringBuilder();
    for (Type type : types) {
      out.append(type.getDescriptor());
    }
    return out.toString();
  }

  // A MixinExtras sugar param, for example @Local or @Share.
  private static boolean sugar(MethodNode method, int index) {
    for (AnnotationNode node : paramAnnotations(method, index)) {
      if (node.desc.startsWith(SUGAR)) {
        return true;
      }
    }
    return false;
  }

  private static AnnotationNode paramAnnotation(MethodNode method, int index, String desc) {
    return annotation(paramAnnotations(method, index), null, desc);
  }

  // The visible and the invisible annotations of one param.
  private static List<AnnotationNode> paramAnnotations(MethodNode method, int index) {
    List<AnnotationNode> result = new ArrayList<>();
    addParam(result, method.visibleParameterAnnotations, index);
    addParam(result, method.invisibleParameterAnnotations, index);
    return result;
  }

  private static void addParam(List<AnnotationNode> out, List<AnnotationNode>[] lists, int index) {
    if (lists != null && index < lists.length && lists[index] != null) {
      out.addAll(lists[index]);
    }
  }

  // Collect each class name that the mixin class refers to.
  private static Set<String> collectReferences(ClassNode node) {
    Set<String> out = new TreeSet<>();
    addName(out, node.superName);
    for (String name : node.interfaces) {
      addName(out, name);
    }
    addDesc(out, node.signature);
    addAnnotations(out, node.visibleAnnotations);
    addAnnotations(out, node.invisibleAnnotations);
    for (FieldNode field : node.fields) {
      addDesc(out, field.desc);
      addDesc(out, field.signature);
      addAnnotations(out, field.visibleAnnotations);
      addAnnotations(out, field.invisibleAnnotations);
    }
    for (MethodNode method : node.methods) {
      addDesc(out, method.desc);
      addDesc(out, method.signature);
      for (String name : method.exceptions) {
        addName(out, name);
      }
      addAnnotations(out, method.visibleAnnotations);
      addAnnotations(out, method.invisibleAnnotations);
      for (int index = 0; index < Type.getArgumentCount(method.desc); index++) {
        addAnnotations(out, paramAnnotations(method, index));
      }
      addValue(out, method.annotationDefault);
      if (method.tryCatchBlocks != null) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
          addName(out, block.type);
        }
      }
      if (method.localVariables != null) {
        for (LocalVariableNode local : method.localVariables) {
          addDesc(out, local.desc);
          addDesc(out, local.signature);
        }
      }
      if (method.instructions == null) {
        continue;
      }
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof TypeInsnNode type) {
          addName(out, type.desc);
        } else if (insn instanceof FieldInsnNode field) {
          addName(out, field.owner);
          addDesc(out, field.desc);
        } else if (insn instanceof MethodInsnNode call) {
          addName(out, call.owner);
          addDesc(out, call.desc);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
          addDesc(out, indy.desc);
          addValue(out, indy.bsm);
          for (Object arg : indy.bsmArgs) {
            addValue(out, arg);
          }
        } else if (insn instanceof LdcInsnNode ldc) {
          if (!(ldc.cst instanceof String)) {
            addValue(out, ldc.cst);
          }
        } else if (insn instanceof MultiANewArrayInsnNode array) {
          addDesc(out, array.desc);
        }
      }
    }
    return out;
  }

  // An internal name, or an array descriptor in a type instruction.
  private static void addName(Set<String> out, String name) {
    if (name == null) {
      return;
    }
    if (name.startsWith("[")) {
      addDesc(out, name);
    } else {
      out.add(name);
    }
  }

  private static void addDesc(Set<String> out, String desc) {
    if (desc == null) {
      return;
    }
    Matcher match = DESC_CLASS.matcher(desc);
    while (match.find()) {
      out.add(match.group(1));
    }
  }

  private static void addAnnotations(Set<String> out, List<AnnotationNode> list) {
    if (list == null) {
      return;
    }
    for (AnnotationNode node : list) {
      addValue(out, node);
    }
  }

  // Annotation values, constants, and bootstrap args.
  private static void addValue(Set<String> out, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof AnnotationNode node) {
      addDesc(out, node.desc);
      if (node.values != null) {
        for (int index = 1; index < node.values.size(); index += 2) {
          addValue(out, node.values.get(index));
        }
      }
    } else if (value instanceof Type type) {
      addDesc(out, type.getDescriptor());
    } else if (value instanceof Handle handle) {
      addName(out, handle.getOwner());
      addDesc(out, handle.getDesc());
    } else if (value instanceof String[] enumValue) {
      addDesc(out, enumValue[0]);
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        addValue(out, item);
      }
    } else if (value instanceof String text) {
      // Selectors and @At targets hold descriptors. @Mixin(targets) holds plain names.
      addDesc(out, text);
      if (PLAIN_CLASS.matcher(text).matches()) {
        out.add(text.replace('.', '/'));
      }
    }
  }

  private static String newType(String target) {
    if (target.startsWith("(")) {
      String ret = target.substring(target.indexOf(')') + 1);
      return Type.getType(ret).getInternalName();
    }
    if (target.startsWith("L") && target.endsWith(";")) {
      return target.substring(1, target.length() - 1);
    }
    return target.replace('.', '/');
  }

  // Owner prefix: Lnet/minecraft/Foo;name(desc). Regex and wildcard name forms return a null name.
  private static Selector parseSelector(String raw) {
    String text = raw.trim();
    if (text.startsWith("/") || text.isEmpty()) {
      return null;
    }
    String[] ownerName = splitOwner(text);
    String rest = ownerName[1];
    int paren = rest.indexOf('(');
    String name = paren < 0 ? rest : rest.substring(0, paren);
    String desc = paren < 0 ? null : rest.substring(paren);
    return new Selector(ownerName[0], name, desc);
  }

  private static String[] splitOwner(String text) {
    if (text.startsWith("L")) {
      int semi = text.indexOf(';');
      int paren = text.indexOf('(');
      if (semi > 0 && (paren < 0 || semi < paren)) {
        return new String[] {text.substring(1, semi), text.substring(semi + 1)};
      }
    }
    return new String[] {null, text};
  }

  private static boolean selectorMatches(Selector selector, MethodNode method) {
    boolean nameMatches =
        selector.name().equals("*")
            ? !method.name.equals("<clinit>") && !method.name.equals("<init>")
            : selector.name().equals(method.name);
    return nameMatches && (selector.desc() == null || selector.desc().equals(method.desc));
  }

  private static Set<String> shadowNames(String name, AnnotationNode shadow) {
    Set<String> names = new LinkedHashSet<>();
    Object prefixValue = value(shadow, "prefix");
    String prefix = prefixValue instanceof String text ? text : "shadow$";
    names.add(name.startsWith(prefix) ? name.substring(prefix.length()) : name);
    names.addAll(strings(value(shadow, "aliases")));
    return names;
  }

  private static void addInflections(Set<String> names, String rest) {
    names.add(Character.toLowerCase(rest.charAt(0)) + rest.substring(1));
    names.add(rest);
    names.add(rest.toUpperCase(java.util.Locale.ROOT));
    names.add(rest.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT));
  }

  // 1 means found. 0 means missing in the game jar. -1 means the chain leaves the game jar.
  private static int findField(
      Game game, String owner, Set<String> names, String desc, Set<String> seen) {
    return findMember(
        game,
        owner,
        seen,
        node -> {
          for (FieldNode field : node.fields) {
            if (names.contains(field.name) && field.desc.equals(desc)) {
              return true;
            }
          }
          return false;
        });
  }

  private static int findMethod(
      Game game, String owner, Set<String> names, String desc, Set<String> seen) {
    return findMember(game, owner, seen, node -> declares(node, names, desc));
  }

  private static int findMember(
      Game game, String owner, Set<String> seen, java.util.function.Predicate<ClassNode> test) {
    if (owner == null || !seen.add(owner)) {
      return 0;
    }
    ClassNode node = game.get(owner);
    if (node == null) {
      return gameClass(owner) ? 0 : -1;
    }
    if (test.test(node)) {
      return 1;
    }
    int result = 0;
    List<String> parents = new ArrayList<>();
    parents.add(node.superName);
    parents.addAll(node.interfaces);
    for (String parent : parents) {
      int found = findMember(game, parent, seen, test);
      if (found == 1) {
        return 1;
      }
      if (found < 0) {
        result = -1;
      }
    }
    return result;
  }

  private static boolean declares(ClassNode node, Set<String> names, String desc) {
    for (MethodNode method : node.methods) {
      if (names.contains(method.name) && method.desc.equals(desc)) {
        return true;
      }
    }
    return false;
  }

  private static List<AnnotationNode> injectors(MethodNode method) {
    List<AnnotationNode> result = new ArrayList<>();
    for (List<AnnotationNode> list :
        List.of(
            Optional.ofNullable(method.visibleAnnotations).orElse(List.of()),
            Optional.ofNullable(method.invisibleAnnotations).orElse(List.of()))) {
      for (AnnotationNode node : list) {
        if (INJECTORS.contains(node.desc)) {
          result.add(node);
        }
      }
    }
    return result;
  }

  private static List<String> targets(ClassNode node) {
    AnnotationNode mixin = annotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN);
    List<String> result = new ArrayList<>();
    Object types = value(mixin, "value");
    if (types instanceof List<?> list) {
      for (Object item : list) {
        result.add(((Type) item).getInternalName());
      }
    }
    for (String name : strings(value(mixin, "targets"))) {
      result.add(name.replace('.', '/'));
    }
    return result;
  }

  private static AnnotationNode annotation(
      List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
    for (List<AnnotationNode> list :
        List.of(
            Optional.ofNullable(visible).orElse(List.of()),
            Optional.ofNullable(invisible).orElse(List.of()))) {
      for (AnnotationNode node : list) {
        if (node.desc.equals(desc)) {
          return node;
        }
      }
    }
    return null;
  }

  private static Object value(AnnotationNode node, String key) {
    if (node == null || node.values == null) {
      return null;
    }
    for (int index = 0; index + 1 < node.values.size(); index += 2) {
      if (key.equals(node.values.get(index))) {
        return node.values.get(index + 1);
      }
    }
    return null;
  }

  private static List<String> strings(Object raw) {
    List<String> result = new ArrayList<>();
    if (raw instanceof String text) {
      result.add(text);
    } else if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof String text) {
          result.add(text);
        }
      }
    }
    return result;
  }

  private static List<AnnotationNode> ats(Object raw) {
    List<AnnotationNode> result = new ArrayList<>();
    if (raw instanceof AnnotationNode node) {
      result.add(node);
    } else if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof AnnotationNode node) {
          result.add(node);
        }
      }
    }
    return result;
  }

  private static boolean gameClass(String internalName) {
    for (String prefix : GAME_PACKAGES) {
      if (internalName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String simple(String internalName) {
    return internalName.substring(internalName.lastIndexOf('/') + 1);
  }

  private static String label(MixinClass mixin) {
    return mixin.name().replace('/', '.');
  }

  private void issue(MixinClass mixin, String text, String version) {
    issues
        .computeIfAbsent(
            label(mixin) + " | " + text, key -> new TreeSet<>(MixinCheck::compareVersions))
        .add(version);
  }

  private int countFor(String version) {
    int count = 0;
    for (TreeSet<String> releases : issues.values()) {
      if (releases.contains(version)) {
        count++;
      }
    }
    return count;
  }

  private static int compareVersions(String left, String right) {
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    for (int index = 0; index < Math.max(a.length, b.length); index++) {
      int av = index < a.length ? Integer.parseInt(a[index]) : 0;
      int bv = index < b.length ? Integer.parseInt(b[index]) : 0;
      if (av != bv) {
        return Integer.compare(av, bv);
      }
    }
    return 0;
  }

  // The issue count includes the absent mixins. Absent mixins are never expected.
  private String summary() {
    return "MIXIN-CHECK issues="
        + (issues.size() + absent.size())
        + " unexpected="
        + unexpected()
        + " expected="
        + expectedBy.size()
        + " stale="
        + stale()
        + " absent="
        + absent.size();
  }

  private String report(
      String pin,
      String loader,
      Path pinJar,
      String gateClass,
      Path expectedFile,
      Set<String> releases,
      List<String> missingJars) {
    StringBuilder out = new StringBuilder();
    out.append("# Mixin check\n\n");
    out.append("Pin `")
        .append(pin)
        .append("`. Loader `")
        .append(loader)
        .append("`. Jar `")
        .append(pinJar.getFileName())
        .append("`.\n\n");
    if (loader.equals("neoforge")) {
      out.append(
          "The check compares each mixin with the NeoForge patched Minecraft jar of each release.\n");
    } else {
      out.append(
          "The check compares each mixin with the Mojang-named Minecraft jar of each release.\n");
    }
    if (gateClass.equals("-")) {
      out.append("No gate class: every mixin is on for each release.\n");
    } else {
      out.append("`")
          .append(gateClass)
          .append(".allow(name, version)` chooses the mixins for each release.\n");
    }
    if (expectedFile == null) {
      out.append("No expected file: each miss fails the check.\n");
    } else {
      out.append("Expected file `")
          .append(expectedFile.getFileName())
          .append("` has ")
          .append(expects.size())
          .append(" entries for this loader. An expected miss does not fail the check.\n");
    }
    out.append(
        "A config with `defaultRequire` 0 skips these misses in silence. With 1 the game fails on them.\n");
    out.append("\nResult: ")
        .append(unexpected())
        .append(" unexpected, ")
        .append(expectedBy.size())
        .append(" expected, ")
        .append(stale())
        .append(" stale.\n");
    out.append("\n## Releases\n\n");
    for (String version : releases) {
      int[] counts = perRelease.getOrDefault(version, new int[] {0, 0});
      out.append("- ")
          .append(version)
          .append(": ")
          .append(counts[0])
          .append(" mixins on, ")
          .append(counts[1])
          .append(" misses.\n");
    }
    for (String version : missingJars) {
      String smoke = loader.equals("neoforge") ? "NeoForge" : "Fabric";
      out.append("- ")
          .append(version)
          .append(": no cached Minecraft jar. Run the ")
          .append(smoke)
          .append(" smoke for it once.\n");
    }
    List<String> all = new ArrayList<>();
    List<String> part = new ArrayList<>();
    List<String> known = new ArrayList<>();
    for (Map.Entry<String, TreeSet<String>> entry : issues.entrySet()) {
      String key = entry.getKey();
      String mixin = key.substring(0, key.indexOf(" | "));
      String text = key.substring(key.indexOf(" | ") + 3);
      TreeSet<String> on = activeOn.get(mixin);
      Expect expect = expectedBy.get(key);
      if (expect != null) {
        known.add(
            "- `"
                + mixin
                + "`: "
                + text
                + ". Releases: "
                + String.join(", ", entry.getValue())
                + ". Reason: "
                + expect.reason);
      } else if (entry.getValue().equals(on)) {
        all.add("- `" + mixin + "`: " + text + ".");
      } else {
        part.add(
            "- `"
                + mixin
                + "`: "
                + text
                + ". Releases: "
                + String.join(", ", entry.getValue())
                + ". The mixin is on: "
                + String.join(", ", on)
                + ".");
      }
    }
    section(out, "Misses on every release where the mixin is on", all);
    section(out, "Misses on part of the line", part);
    section(
        out,
        "Listed in a config, absent from the jar",
        absent.stream().map(item -> "- `" + item + "`.").toList());
    section(out, "Expected misses", known);
    section(
        out,
        "Stale expectations",
        expects.stream()
            .filter(expect -> !expect.used)
            .map(
                expect ->
                    "- Line " + expect.line + ": `" + expect.source() + "`. No miss matches it.")
            .toList());
    section(
        out,
        "Game-package classes in the pin jar",
        stubs.stream().map(item -> "- `" + item.replace('/', '.') + "`.").toList());
    out.append("\n## Not checked\n\n");
    out.append("- `@Pseudo` mixins: ").append(pseudo.size()).append(".\n");
    out.append("- Mixins with targets outside Minecraft only: ")
        .append(external.size())
        .append(".\n");
    out.append("- `slice`, `CONSTANT`, `LOAD`, `STORE`, locals, and loader patches.\n");
    return out.toString();
  }

  private static void section(StringBuilder out, String title, List<String> lines) {
    out.append("\n## ").append(title).append("\n\n");
    if (lines.isEmpty()) {
      out.append("None.\n");
      return;
    }
    for (String line : lines) {
      out.append(line).append('\n');
    }
  }
}
