# Architecture

## Pins

A pin is one Minecraft release that the mod compiles against. Each pin builds one jar per loader
(NeoForge, Fabric). The jar runs on every release of its line.

| Pin | Minecraft line | Java | Source line |
| --- | --- | --- | --- |
| `1.21.1` | 1.20.5–1.21.1 | 21 | `rl` |
| `1.21.4` | 1.21.2–1.21.4 | 21 | `rl` |
| `1.21.10` | 1.21.5–1.21.10 | 21 | `rl` |
| `1.21.11` | 1.21.11 | 21 | `id` |
| `26.2` | 26.1–26.3 | 25 | `id` |

Gradle projects: `:mod:<pin>:<loader>` in `mod/versions/<pin>/<loader>`. Settings finds the pins
from these directories. `buildSrc/src/main/kotlin/psymc/Versions.kt` describes each pin:

- `neo`: the NeoForge build that the NeoForge jar compiles against.
- `fabricApi`: the Fabric API build that the Fabric jar compiles against.
- `minecraftRange`, `neoRange`, `loaderRange`: the ranges in the loader metadata. Fabric gets a
  predicate made from `minecraftRange`.
- `lwjglRuntime`: where the NeoForge headless client puts HeadlessMc (`additional` up to 1.21.4,
  `runtime` from 1.21.10).
- `namedJar`: the game jar is not obfuscated (26.x). Fabric uses `fabric-loom` without a remap.
- `libraries`: Modrinth mods that the mod needs at run time (see Libraries).

### Add a pin

1. Add an entry to `modPins` in `Versions.kt`.
2. Copy `mod/versions/<last pin>` to `mod/versions/<new pin>`. Pick the Loom plugin in the Fabric
   build file: `fabric-loom-remap` on an obfuscated release, `fabric-loom` on a named release.
3. Add `versionProperties/<release>.properties` for each release of the new line.
4. Run `compileAll`, then `versionSmoke "-Ppin=<new pin>"` on both loaders.

## Versions of a release

`versionProperties/<release>.properties` describes one release for the launches off the pin's own
release (`versionSmoke`, `mixinAudit`, `serverSmoke -Pline`, `versionPlay`):

- `pin`: the pin whose jar runs on this release.
- `obfuscated`: `true` up to 1.21.11. It selects the Fabric launch (`obfuscated` or `named`).
- `fabric_api`, `neoforge_loader`: the loader builds of the launch.
- `builds_for`: the loaders of the release. The smoke tasks skip the other loaders.
- `compat_<mod>_<loader>`: Modrinth version ids for `-Pcompat`.
- `library_<slug>_<loader>`: a library build for this release. Without it the launch uses the
  build of the pin.

## Source layers

Sources are in `mod/src`. Lowest layer first:

1. `common`
2. the line: `rl` (ResourceLocation) or `id` (Identifier)
3. the pin: `<pin>`
4. the loader: `neoforge` or `fabric`
5. the loader line: `<loader>/<line>`
6. the loader pin: `<loader>/<pin>`

Each layer has `java` and `resources`. A higher layer replaces the file with the same relative
path. A pin file replaces the whole file. The wiring is `layers()` in
`buildSrc/src/main/kotlin/psymc/Access.kt`, used by `gradle/mod-common.gradle.kts`.

Put a type that Minecraft renamed behind a small class in the line layer (`ModIds` in the
example). Put a method that changed on one release into the pin layer. Keep the rest in `common`.

## Loaders

- NeoForge: ModDevGradle (`gradle/mod-neoforge.gradle.kts`). Entry: `@Mod` class in
  `src/neoforge`. Metadata: `src/neoforge/resources/META-INF/neoforge.mods.toml`.
- Fabric: Fabric Loom (`gradle/mod-fabric.gradle.kts`). Entry: `ModInitializer` in `src/fabric`.
  Metadata: `src/fabric/resources/fabric.mod.json`. Fabric API is a required mod.
- The metadata files use `${...}` values from `gradle.properties` and `Versions.kt`.
- Mixin configs are in `common/resources` and are named in both metadata files. NeoForge uses the
  Mojang names at run time. On an obfuscated Fabric pin, `remapJar` writes the intermediary names
  into the mixin annotations (`init` becomes `method_25426`). The jar needs no refmap.
- `mergeJars` merges the two jars of a pin into one jar with Forgix
  (`mod/versions/<pin>/build/forgix/`). Merge a pin only after its two jars run in the game.

## NilLoader

The third leaf `:mod:<pin>:nil` builds a nilmod for [NilLoader](https://git.sleeping.town/Nil/NilLoader):
a Java agent that patches classes with Mini (bytecode) patches. A player adds
`-javaagent:NilLoader.jar` to the game and puts the jar into `mods/` (or `nilmods/` beside
another loader). NilLoader runs on the vanilla game, and beside Fabric Loader or NeoForge.

- No Mixin: the leaf drops the `mixin` and `mixinplugin` packages and `*.mixins.json`. Mini patches in `src/nil`
  do the same work (`TitleScreenPatch` for `TitleScreenMixin`). Keep mixins in a `mixin` package.
- Entry: a `Runnable` named in `src/nil/resources/<mod_id>.nilmod.css` (`premain`). It runs before
  the game classes load: register the patches there, and call the shared code from a patch
  (`BootstrapPatch`).
- The build does not use NilGradle (it does not apply on Gradle 9). `gradle/mod-nil.gradle.kts`
  and `buildSrc/src/main/kotlin/psymc/` (`Minecraft.kt`, `NilMappings.kt`) do the work:
  - Download the client jar and its ProGuard mappings from the launcher metadata. Remap the jar
    to Mojang names with ASM (cache: `<Gradle user home>/caches/psymc/minecraft/<release>`).
    Compile against it and the game libraries.
  - On an obfuscated pin, task `nilMappings` writes `META-INF/nil/mappings.json`: the game
    classes and members that the mod bytecode and the `@Patch` annotations use, in these sets:
    one per obfuscated release of the pin line (the obfuscated names differ on each release),
    `default` (the pin release), `intermediary` (beside Fabric Loader), and `mojmap` (beside
    NeoForge, no change). A member that the mod reaches through a subclass gets an entry on that
    subclass: NilLoader cannot read the game hierarchy in Mojang names at run time.
  - A named pin (26.x) needs no mappings.
- The entry selects the set at run time (`ExampleModNil.selectMappings`): Fabric Loader on the
  classpath gives `intermediary`, NeoForge gives `mojmap`, else the release id from `version.json`
  of the client jar.
- Do not put a game name in a string that NilLoader cannot remap. A hook takes `Object` and casts
  it in Java code (`NilHooks.onServerStarted`).

### Turn NilLoader off, or remove it

- Off: remove `nil` from `loaders` in `gradle.properties`. Settings then has no nil leaves,
  `compileAll` skips them, and `-Ploader=nil` stops with an error. The files stay.
- Remove: turn it off, then delete `mod/versions/*/nil`, `mod/src/nil`, `gradle/mod-nil.gradle.kts`,
  `version-smoke/vanilla`, `gradle/nil-check.gradle.kts`, `buildSrc/src/main/kotlin/psymc/NilMappings.kt`,
  `buildSrc/src/main/kotlin/psymc/NilCheck.kt`, `nilloader_version` and `nil_smoke_markers` in
  `gradle.properties`, the nil entries of `gradle/smoke-expected.txt`, and the NilLoader sections of
  the docs. Keep `Minecraft.kt` and
  `Mappings.kt`: `mixinCheck` and `mcInspect` use them.

## Libraries

`libraries` in a pin (`Versions.kt`) lists Modrinth mods by slug and version per loader. The pin
compiles against them (Fabric: `modImplementation`, NeoForge: `implementation`; not in the jar).
The launches put them beside the pin jar. A release can name another build in
`versionProperties` (`library_<slug>_<loader>`). Declare them as dependencies in both metadata files.

## Mixins

- `examplemod.mixins.json` sets `defaultRequire` to 1. A missed injection stops the game.
  A mod for many releases can set 0 and run `mixinCheck`.
- `mixinCheck` can use a gate class: `static boolean allow(String mixinClass, String release)`.
  The gate keeps a mixin off the releases where its target differs. A config plugin calls the same
  gate in `shouldApplyMixin`. Name the class in `mixin_gate_class` in `gradle.properties`.
- Mixins that a config plugin adds from `getMixins()` are not in a config file. Name their package
  in `mixin_plugin_package`.
- `mixinCheck` also compares the handler signatures with the target (`@Inject` arguments,
  `@Local(argsOnly = true)`, `@ModifyExpressionValue`, `@ModifyArg`, `@WrapOperation`,
  `@Redirect`), and reports a mixin that uses a compile stub of a game class on a release without
  that class. Expected misses, each with a reason, go into `gradle/mixin-check/expected.txt`.

### Rules for mixins on many releases

- **Duck interfaces go into their own mixin.** NeoForge drops the whole mixin when one injector
  or one local variable in it fails. The duck interface of that mixin is then not on the target
  class, and a call through it gives `AbstractMethodError`. Put the duck methods in a separate
  `*_Duck` mixin with no injectors.
- **A mixin for part of the releases goes through `getMixins()`.** Do not list it in the config:
  Mixin does not call `shouldApplyMixin` for the mixins that `getMixins()` returns. The config
  plugin returns them from `getMixins()` and filters that list itself through the version gate:

  ```java
  public final class ExampleMixinPlugin implements IMixinConfigPlugin {
    private static final List<String> PIN_ONLY = List.of("TitleScreen_1_21_2Mixin");

    @Override
    public List<String> getMixins() {
      String release = MixinGate.currentRelease(); // from the loader: FabricLoader, FMLLoader
      return PIN_ONLY.stream().filter(name -> MixinGate.allow(name, release)).toList();
    }

    @Override
    public boolean shouldApplyMixin(String target, String mixin) {
      return MixinGate.allow(mixin, MixinGate.currentRelease());
    }
    // other methods: empty
  }
  ```

  `MixinGate.allow(String mixinClass, String release)` is the same gate that `mixinCheck` reads
  (`mixin_gate_class`, and the package in `mixin_plugin_package`).

  The example has it: `mixinplugin/MixinGate` (a table: mixin to the first release where it
  applies; the running release from `version.json` of the game, so no loader API),
  `mixinplugin/ExampleMixinPlugin` (the `plugin` of `examplemod.mixins.json`), and
  `mixin/GatedTitleScreenMixin` (on from 1.21; off on 1.20.5 and 1.20.6). The plugin and the gate
  are not in the mixin package: Mixin does not let other code load classes of that package.
  The gate must not use Mixin or a loader: `mixinCheck` loads it from the jar without them.
