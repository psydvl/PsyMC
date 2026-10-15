# AGENTS.md — Example Mod

One Minecraft mod for NeoForge, Fabric, and NilLoader on several Minecraft lines. Project `:mod`
(dir `mod/`), leaves `:mod:<pin>:<loader>` with `<loader>` = `neoforge`, `fabric`, `nil`
(`loaders` in `gradle.properties`; NilLoader is optional).
Pins: `1.21.1`, `1.21.4`, `1.21.10`, `1.21.11`, `26.2`. A pin jar runs on every release of its line.

Machine-local notes (gitignored): `.local/AGENTS.md` (current goal, live client, open bug) and
`.local/TODO.md` (one line per item). Read them first when they exist.

## Commands

Run Gradle as `mise exec java@25 -- .\gradlew.bat <task> --console=plain --no-daemon`.
Quote version properties in PowerShell: `"-Ppin=1.21.4"`.

| Task | Command |
| --- | --- |
| Compile one pin | `:mod:<pin>:<loader>:compileJava` |
| Compile all | `compileAll` |
| Format | `spotlessApply` (check: `spotlessCheck`) |
| Mixin selectors, no game | `mixinCheck` and `mixinCheck "-Ploader=neoforge"` |
| Mixin apply in a client | `mixinAudit "-Ploader=<loader>" "-Ppin=<pin>"` |
| Start on each release | `versionSmoke "-Ppin=<pin>" -PmainOthers` |
| Network path | `serverSmoke "-Ploader=<loader>" "-Ppin=<pin>"` |
| Play the pin release | `:mod:<pin>:<loader>:runClient -Pplay ["-PtestWorld=<name>"]` |
| Play another release | `versionPlay "-Ploader=<loader>" "-Pmc=<release>" ["-Pcompat=sodium,iris"]` |
| One jar for both loaders | `mergeJars` |
| Game method on each release | `mcInspect "-Pclass=<class>" "-Pmethod=<name>" ["-Ppin=<pin>"] ["-Ploader=neoforge"] [-Pbytecode]` |
| Lambda names for a selector | `mcLambdas "-Pclass=<class>" "-Pmethod=<name>" ["-Ppin=<pin>"]` |
| What failed in the last launch | `lastRun ["-Ppin=<pin>"] ["-Ploader=<loader>"]` |
| Make the test world | `makeTestWorld ["-Pname=<name>"] ["-Pmc=<release>"]` |
| NilLoader in the vanilla game | `versionSmoke "-Ploader=nil" "-Ppin=<pin>"`, `serverSmoke "-Ploader=nil"`, `versionPlay "-Ploader=nil" "-Pmc=<release>"` |
| Nil patches, no game | `nilCheck ["-Ppin=<pin>"] ["-Pmc=<release>"]` |

- Run one game launch at a time.
- Do not pipe or redirect Gradle output when a daemon can start.
- Reports and logs: `build/version-smoke/`.

## References

| Need | File |
| --- | --- |
| Pins, source layers, loaders, metadata | `docs/architecture.md` |
| Build, checks, play runs, check details | `docs/testing.md` |
| Compile versions of a pin | `buildSrc/src/main/kotlin/psymc/Versions.kt` |
| NilLoader leaf, nil mappings | `docs/architecture.md` (NilLoader), `gradle/mod-nil.gradle.kts` |
| Run versions of a release | `versionProperties/<release>.properties` |
| Server smoke scenario | `serverScenario` in `gradle/version-smoke.gradle.kts`, `docs/testing.md` |
| Expected mixinCheck misses | `gradle/mixin-check/expected.txt` (each with a reason) |
| Known smoke failures | `gradle/smoke-expected.txt` (each with a reason and a link) |
| Manual check list, test worlds | `docs/testing.md` (In-game checklist), `testworlds/README.md` |

## Key conventions

- A higher source layer replaces the file with the same relative path. A pin file replaces the
  whole file. Keep a pin-only body in that pin.
- A change on one pin is not finished there. Make the matching change on the other pins in the
  same edit, fitted to each pin's API. Skip a pin only when it has no method for that job.
- Java comments: ASD-STE100 Simplified Technical English. Short sentences, one action each.
- Repository scripts are Gradle tasks in the Kotlin DSL (`*.gradle.kts`). No Python, shell,
  PowerShell, or Groovy DSL scripts.
- Scratch files and compile logs go in `.local/work/`. Keep the repository root clean.
- Run `spotlessApply` before a commit. Run `mixinCheck` after a mixin change; it must stay clean.
  A miss that cannot be fixed goes into `gradle/mixin-check/expected.txt` with its reason.
- Before you read game code with `javap`, use `mcInspect` and `mcLambdas`. After a launch, use `lastRun`.
- Mixin rules for many releases (duck interfaces, `getMixins()` with the version gate): `docs/architecture.md`.
- A mixin needs a matching Mini patch in `src/nil`; run `nilCheck` after a patch change. Keep mixins in a
  `mixin` package and Mixin plugin code in `mixinplugin`: the nil leaf drops both.
- A mixin for part of a pin line: an entry in `MixinGate` and `ExampleMixinPlugin` (`docs/architecture.md`).

## When a bug needs a running game

Ask the person to do the check. Give one clear request per step: what to do in the game, and
which file to send back (`logs/latest.log`, the file in `crash-reports/`). Name the game
directory. Put a temporary log in the mod when the value is not visible from outside. Remove
that log after the cause is known.
