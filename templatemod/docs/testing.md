# Testing

Commands to build, check, and play the pins. Pins and source layers: [architecture.md](architecture.md).

## Run Gradle

mise gives the JDKs. Gradle finds Java 21 and 25 through `ASDF_DATA_DIR` in `mise.toml`.
Without mise, the foojay plugin in `settings.gradle.kts` downloads the missing toolchains.

```powershell
mise exec java@25 -- .\gradlew.bat <tasks> --no-daemon
```

Do not send the Gradle output into a PowerShell pipe or redirect (`| Select-Object`, `*> file`)
when a daemon can start. A new daemon keeps the output handles open, and PowerShell waits for
them. Use `--no-daemon`, or `Start-Process` with `-RedirectStandardOutput` to a file.

In PowerShell, put quotes around a property with a version: `"-Ppin=1.21.4"`.
Without quotes PowerShell cuts `-Ppin=1.21.4` into `-Ppin=1` and `.21.4`.

Gradle configures only the pin projects that the requested tasks need (rule in `settings.gradle.kts`):

- A task path such as `:mod:1.21.4:fabric:runClient` configures that pin and loader only.
- `versionSmoke`, `mixinAudit`, `mixinCheck`, `serverSmoke`, `versionPlay`, `spotless*`, and `pins`
  configure no pin project. Their nested Gradle runs configure the projects they use.
- `"-Ppins=1.21.4,26.2"` configures these pins on all loaders.
- Any other task (for example `compileAll` or `mergeJars`) configures all projects.

## Build

| Command | What it does |
| --- | --- |
| `compileAll` | Compile every pin on every loader. |
| `:mod:<pin>:<loader>:compileJava` | Compile one pin on one loader. |
| `:mod:<pin>:fabric:remapJar` | Fabric jar of an obfuscated pin (`jar` on 26.2). |
| `:mod:<pin>:neoforge:jar` | NeoForge jar of one pin. |
| `mergeJars` | One Forgix jar per pin for both loaders. |
| `spotlessApply` | Format Java (Google Java Format), Kotlin scripts (ktlint), and text files. |
| `:mod:<pin>:<loader>:test` | JUnit tests from `mod/src/test/java`. |

## Automatic checks

All checks take these properties:

| Property | Meaning |
| --- | --- |
| `-Ploader=<loader>` | `fabric` (default), `neoforge`, or `nil` (`versionSmoke`, `versionPlay`, `serverSmoke`). |
| `-Ppin=<pin>[,<pin>]` | Only these pins. |
| `-Pmc=<release>[,<release>]` | Only these Minecraft releases. |
| `-PmainOthers` | Every release of `-Ppin`, and the own release of the other pins. |

Run one game launch at a time.

| Command | What it checks | Report |
| --- | --- | --- |
| `versionSmoke` | Each release starts the client (`Sound engine started`). `-Pheaded` shows the window. | `build/version-smoke/report-<loader>.md` |
| `mixinCheck` | Mixin selectors and handler signatures against the game jar. No game starts. Fails on a miss that is not in `gradle/mixin-check/expected.txt`. | `build/version-smoke/mixin-check/<pin>-<loader>.md` |
| `mixinAudit` | Loads each mixin target in a real client. Lists mixin apply errors. | `build/version-smoke/audit-<loader>.md` |
| `serverSmoke` | Dedicated server and headless client. The client joins, then the scenario runs. | `build/version-smoke/server-<loader>.md` |
| `nilCheck` | The Mini patch targets and the game references of each nil jar against the game jar of each release; the mapping set of each obfuscated release. No game starts. | `build/version-smoke/nil-check/<pin>.md` |

Logs of each launch: `build/version-smoke/logs/<loader>/`.

```powershell
mise exec java@25 -- .\gradlew.bat versionSmoke "-Ppin=1.21.4" -PmainOthers --no-daemon
mise exec java@25 -- .\gradlew.bat mixinCheck "-Ploader=neoforge" --no-daemon
mise exec java@25 -- .\gradlew.bat mixinAudit "-Ploader=fabric" "-Ppin=1.21.4" --no-daemon
mise exec java@25 -- .\gradlew.bat serverSmoke "-Ploader=neoforge" "-Ppin=1.21.4" --no-daemon
mise exec java@25 -- .\gradlew.bat serverSmoke "-Ploader=fabric" "-Ppin=1.21.4" -Pline --no-daemon
```

The pin's own release runs the pin projects. Another release runs the pin jar in a
`version-smoke` launch:

| Launch | Releases |
| --- | --- |
| `version-smoke/neoforge` | NeoForge, all releases (ModDevGradle) |
| `version-smoke/obfuscated` | Fabric up to 1.21.11 (Loom remaps the pin jar) |
| `version-smoke/named` | Fabric 26.x (the jars go into `mods/`) |
| `version-smoke/vanilla` | NilLoader, all releases: the vanilla client from the launcher metadata with `-javaagent:NilLoader` |
| `version-smoke/mixin-audit` | The audit mod; added with `-PmixinAudit` |

A check fails (exit code not 0) after its report is complete when a release failed. CI uses that.
A known failure goes into `gradle/smoke-expected.txt` (task, loader, release, reason with a link):
it then counts as "expected". An expected release that passes is reported; remove its entry.

### Main and heavy smoke

- Main smoke: the pin's own release. It checks that the pin still starts.
- Heavy smoke: every release in the pin's line. Run it when the pin jar must stay compatible with
  the whole line.
- After a change on one pin: heavy smoke for that pin, main smoke for the other pins
  (`versionSmoke "-Ppin=<pin>" -PmainOthers`).
- Before a release: `versionSmoke` without properties, on both loaders.

### Check details

- `versionSmoke`: HeadlessMc replaces LWJGL; the client makes no window and no real OpenGL
  calls. A release counts as started at the sound startup: `Sound engine started`, or
  `Error starting SoundSystem` (HeadlessMc has no OpenAL 1.1; a mod can add an OpenAL stub mixin
  for the smoke). A crash fails the release. A NeoForge release fails when FML refuses the pin jar
  (`InvalidModFileException`, missing mandatory dependencies).
- 26.3 opens its window through SDL. HeadlessMc 3.0 (`headlessmc_version` in `gradle.properties`)
  has the SDL stubs; 2.10 stops there (`Unable to initialize SDL`). The jar comes from the GitHub
  releases of HeadlessMc (`headlessmc-lwjgl-<version>-no-asm.jar`): JitPack has no build of 3.0.
- `-Ploader=nil`: every release runs the nil jar of its pin (`builds_for` does not apply). A release
  counts as started only when the output has every line of `nil_smoke_markers`
  (`gradle.properties`): the lines that the patches print (`[examplemod] loaded` from
  `BootstrapPatch`). NilLoader errors (`not found while patching`, `Failed to patch`) fail it. The
  headless vanilla client does not reach the title screen, so the `TitleScreen` patch has no line
  there; `nilCheck` checks its target.
- `serverSmoke -Ploader=nil` runs the vanilla dedicated server and the vanilla client, both with
  NilLoader, in `version-smoke/vanilla`. The server jar of Mojang is a bundler: the launch takes
  the server jar and its libraries out of it (`MinecraftRelease.serverClasspath`) and starts the
  server with a flat classpath, so that NilLoader puts the mod classes beside the game classes. The
  scenario skips the steps with the mod block (the nil jar has none).
- `nilCheck` reads the nil jar of each pin: the `@Patch.Class` and `@Patch.Method` targets, and
  each game class, method, and field that the mod bytecode uses. It looks for them in the client
  jar with Mojang names of each release of the pin line (through the super classes). On an
  obfuscated release it also checks that `mappings.json` has the set of that release and an entry
  for each used name that the release renames. A Mini patch on the obfuscated releases must not
  read a local variable at a return: ProGuard cuts the stack map frames, and the frame there can
  have no locals, not even `this` (see `DedicatedServerPatch`). The
  launch has its own assets directory (see `MinecraftRelease.assetsDir`). The game is offline:
  the 401 errors of the Mojang services in the log are normal. Known open issue, not of NilLoader:
  the headless vanilla client of 1.21.2 and 1.21.3 fails without NilLoader too. HeadlessMc stubs
  `MemoryUtil.getAllocator(boolean)` and returns null for that interface type. 1.21.2 and 1.21.3
  make a `Tesselator` in `RenderSystem.<clinit>`, and `ByteBufferBuilder` calls `malloc` on the null
  allocator. 1.21.4 and later make it later. A redirection through `RedirectionApi` does not change
  it. Reported: https://github.com/headlesshq/headlessmc/issues/471.
  The headless vanilla client of 26.1, 26.1.1, and 26.1.2 fails without NilLoader too:
  `GLFWErrorCallback.create` fails under the HeadlessMc stubs, and `GLFWErrorScope.close` stops the
  game ("GLFW error callback has unexpectedly changed during this scope"); reported:
  https://github.com/headlesshq/headlessmc/issues/472. Both are in
  `gradle/smoke-expected.txt`. The Fabric and NeoForge launches of these releases start. `mixinCheck`, `mixinAudit`, and `serverSmoke` do not support `nil`.
- Headless NeoForge clients write `earlyWindowControl = false` into `config/fml.toml` of the game
  directory. FML ignores the system property. Its early window then runs on the HeadlessMc GL
  stubs, and on FML 6.0.18 (1.21.4) it can fail in a race (`NullPointerException` in `GlDebug`).
- `mixinCheck`: reads the jar of each pin with ASM (`gradle/mixin-check/MixinCheck.java`) and
  compares each mixin with the Mojang-named game jar of each release: on Fabric the vanilla client
  with Mojang names (downloaded and remapped once, see `MinecraftRelease`), on NeoForge the patched
  jar of ModDevGradle (`version-smoke/neoforge/build/moddev/artifacts` or the pin build dir; a
  release without one is listed in the report, run a NeoForge smoke on it once). NeoForge
  recompiles the game, so a lambda has the javac name (`lambda$init$0`); Fabric has the
  intermediary name. Put both names in the `method` selector (`mcLambdas` lists them). The check
  reads:
  - method selectors, `@At` `INVOKE`, `FIELD`, and `NEW` targets with ordinals, `@Shadow`,
    `@Overwrite`, `@Accessor`, and `@Invoker`;
  - handler signatures: `@Inject` takes a prefix of the target arguments, then `CallbackInfo` (void
    target) or `CallbackInfoReturnable`; the type of `@Local(argsOnly = true)` is a target
    argument; `@ModifyExpressionValue`, `@ModifyArg`, `@WrapOperation`, and `@Redirect` use the
    types of the matched call. A handler from another release passes the selector check but fails
    here: Mixin drops it in the game with `Invalid descriptor`;
  - compile stubs: a game class in the pin jar only for the compile. A mixin that uses one on a
    release without that class is a miss: the stub hides a wrong signature from the compiler.
  It does not check `slice`, `CONSTANT`, `LOAD`, `STORE`, or loader patches.
  Expected misses go into `gradle/mixin-check/expected.txt`: loader, mixin class, a text of the
  miss, and the reason (example: one reach hook per loader, because NeoForge uses `canReach` and
  Fabric `distanceToSqr`). The report lists them apart, and lists stale entries. Any other miss
  fails the task.
- `versionSmoke` also fails a release, and stops its client at once, at `Caught error loading
  resourcepacks` (for example, a shader imports a file that the release does not have; the client
  hangs at the loading screen) and `Unreported exception thrown`. `Mixin apply for mod <mod_id>
  failed` goes into the report as a warning: Mixin drops that mixin and the game goes on.- `mixinAudit`: the audit mod loads each target class of the configs in `mixin_configs`
  (`gradle.properties`), prints `MIXIN-AUDIT DONE`, and the launch stops the client.
- `serverSmoke`: the client joins through TCP with `--quickPlayMultiplayer`. Then the steps of
  `serverScenario` in `gradle/version-smoke.gradle.kts` run: each step can send a console command
  and waits for a line in the server or client output. A disconnect, a packet error, an unknown
  command, or a crash stops the run. Ports: 25581 NeoForge, 25582 Fabric.

## Harmless log lines

These lines come in many launches. They are not the cause of a failure (`lastRun` hides most of them):

- `WARNING: A restricted method in java.lang.System has been called` (`System::load` by
  `net.rubygrapefruit.platform`): JDK 24+ warns when Gradle loads its native library (JEP 472).
  `--enable-native-access=ALL-UNNAMED` in `org.gradle.jvmargs` and in `GRADLE_OPTS` (`mise.toml`,
  CI) turns it off. A game on Java 25 can print the same for JNA and `sun.misc.Unsafe` (Guava).
- `Invalid registry value type detected for PerfOS counters. Should be REG_DWORD`: OSHI in the game
  reads `HKLM\SYSTEM\CurrentControlSet\Services\PerfOS\Performance\Disable Performance Counters`,
  finds a string, and ignores it. A setting of that computer; the game is not affected.
- The 401 errors of authlib and Realms: the dev launches are offline.
- `Error starting SoundSystem` / `OpenAL 1.1 not supported`: HeadlessMc has no sound.
## serverSmoke scenario

After the client joins, `serverSmoke` runs the steps of `serverScenario` in
`gradle/version-smoke.gradle.kts`. A step sends one console command (or none), waits for a text in
the server or client output, and can pause. A disconnect or a crash in any output fails the run.

```kotlin
// name, command (or null), side ("server" or "client"), expected text, pause in ms
ScenarioStep("place the mod block", "setblock 2 -60 2 $modId:probe_block", "server", "Changed the block"),
ScenarioStep("go to the nether", "execute in minecraft:the_nether run tp @a 0 100 0", "server", "Teleported", 5000),
ScenarioStep("come back to the block", "execute in minecraft:overworld run tp @a 2 -59 4", "server", "Teleported", 5000),
```

The example puts the mod block into the world, then sends its chunk again: the player leaves the
dimension and comes back. A block state without a numeric id breaks the chunk packet, and the
client disconnects. Add a step like this for each new kind of content (blocks, entities, items in
inventories): the join alone does not send it. Use the console texts of the English server
(`Changed the block`, `Teleported`, `Test passed` for `execute if`).

## Look at the game code

No game starts. The vanilla jar is the client with Mojang names (from the launcher metadata). The
NeoForge jar is the patched game of ModDevGradle: a release has one after a NeoForge build or smoke
on it.

| Command | Output | Report |
| --- | --- | --- |
| `mcInspect "-Pclass=<class>" "-Pmethod=<name or name(desc)>" ["-Pmc=..."/"-Ppin=..."] ["-Ploader=neoforge"] [-Pbytecode]` | For each release: descriptors, access, every call and field use in the method with its `@At` ordinal; with `-Pbytecode` the bytecode. | `build/version-smoke/inspect/<Class>.<method>-<loader>.md` |
| `mcLambdas "-Pclass=<class>" "-Pmethod=<name>" ["-Pmc=..."/"-Ppin=..."]` | The synthetic lambdas of the method (nested ones too) for each release, vanilla and NeoForge, and one `method = {...}` selector with all names. NeoForge recompiles the game, so its lambda numbers differ. | `build/version-smoke/inspect/<Class>.<method>-lambdas.md` |
| `lastRun ["-Ppin=<pin>"] ["-Ploader=<loader>"]` | For each game directory, newest first: the crash report of the last launch and the error lines of `logs/latest.log`, without the known noise of an offline launch (authlib, Realms, data fixer, OpenAL). | `build/version-smoke/last-run.md` |
## Play

`-Pplay` keeps the client window or the server open. Without `-Pplay`, `runClient` and
`runServer` are smoke runs that stop by themselves.

```powershell
mise exec java@25 -- .\gradlew.bat :mod:1.21.4:fabric:runClient -Pplay --no-daemon
mise exec java@25 -- .\gradlew.bat :mod:1.21.4:neoforge:runServer -Pplay --no-daemon
```

| Run | Directory |
| --- | --- |
| Client | `mod/versions/<pin>/<loader>/build/client` |
| Server | `mod/versions/<pin>/<loader>/build/server` (offline mode) |

`-PtestWorld=<name>` opens a prepared world from `testworlds/<name>` at start, in `runClient -Pplay`
and in `versionPlay` (see [../testworlds/README.md](../testworlds/README.md); `makeTestWorld` makes `testworlds/flat`). Then a check starts in
the world with the needed blocks, not at world creation.

`runClient -Pplay` runs the pin's own release. For another release of the line use `versionPlay`.
It builds the pin jar and starts it on that release with a window:

```powershell
mise exec java@25 -- .\gradlew.bat versionPlay "-Ploader=neoforge" "-Pmc=1.21.2" --no-daemon
mise exec java@25 -- .\gradlew.bat versionPlay "-Ploader=fabric" "-Ppin=1.21.4" "-Pcompat=sodium,iris" --no-daemon
```

The game directory is `version-smoke/<launch>/build/client`. The task prints it at the end.

## Real OpenGL and other render mods

- `-PrealGl` makes a `versionSmoke`, `mixinAudit`, or `serverSmoke` client use a real OpenGL
  context with a hidden window (`gradle/agents/HiddenWindowAgent.java`). Use it to check render code.
- `-Pcompat=sodium,iris,dh` adds Sodium, Iris, and Distant Horizons from Modrinth to a
  `versionSmoke`, `mixinAudit`, or `versionPlay` launch. `iris` also adds `sodium`. The Modrinth
  version ids are `compat_<mod>_<loader>` in `versionProperties`. A release without an id runs
  without that mod; the build prints a warning. These mods need real OpenGL, so `-Pcompat` turns
  on the hidden window too. `-Pheaded` shows the window.
- With `iris`, the launch puts the shader pack `compat_shaderpack` (`gradle.properties`) into
  `shaderpacks/` and turns it on. `-PnoShaderpack` keeps Iris without a pack.
- The `obfuscated` launch puts libraries and `-Pcompat` mods (with their nested jars) on the
  classpath through Loom. The runtime remap of Fabric Loader fails on some of them.
  `-PcompatMods` copies them into `mods/` instead; use it to compare the two paths.
- On NeoForge, `-Pcompat` sets `enableB3DValidationLayer = false` in `config/neoforge-client.toml`.
  Iris fails on the validation wrapper of the GPU device.

Send back `logs/latest.log` and, after a crash, the file in `crash-reports/`.

## CI

`.github/actions/build` (called by `.github/workflows/build.yml`) runs: `spotlessCheck`,
`compileAll`, `mergeJars`, `mixinCheck` on both loaders, and `serverSmoke` on both loaders (the
own release of each pin). With `nil` in `loaders` it also runs `nilCheck` and `serverSmoke
-Ploader=nil`. It uploads the jars and `build/version-smoke/`. A clean `mixinCheck`
needs every known miss in `gradle/mixin-check/expected.txt`, with its reason.

## In-game checklist

Copy this list for a manual check of a pin and a loader. Use a test world (`-PtestWorld`). Write
the result of each line: OK, or what went wrong and the file to read.

```markdown
### <pin> <loader> <release> — <date>

Run: `<command>`. Game directory: `<dir>`.

- [ ] The game starts. The title screen shows. `logs/latest.log` has `[examplemod] loaded`.
- [ ] The test world opens. No error screen, no disconnect.
- [ ] <Feature 1>: <what to do>. Expected: <what you see>.
- [ ] <Feature 2>: <what to do>. Expected: <what you see>.
- [ ] Leave the dimension and come back. The mod blocks are still there.
- [ ] Save and quit, open the world again. The mod state is the same.
- [ ] A dedicated server (`runServer -Pplay`) with this client: join, the same features work.
- [ ] `lastRun` shows no new error of the mod.

Send back: `logs/latest.log`, and the file in `crash-reports/` after a crash.
```
