# Test worlds

Prepared worlds for a quick manual check. A client run opens one at start:

```powershell
mise exec java@25 -- .\gradlew.bat :mod:1.21.4:fabric:runClient -Pplay "-PtestWorld=flat" --console=plain --no-daemon
mise exec java@25 -- .\gradlew.bat versionPlay "-Ploader=neoforge" "-Pmc=1.21.2" "-PtestWorld=flat" --console=plain --no-daemon
```

The run copies `testworlds/<name>` into `saves/<name>` of the client and starts with
`--quickPlaySingleplayer <name>`. It also puts `testworlds/hotbar.nbt` into the client: the saved
hotbars of the creative inventory. Press X+1 in the world to load the first one (the mod block,
debug stick, command block, structure block, barrier, light, stone, chest, crafting table). Each
launch starts from a fresh copy. `-PkeepTestWorld` keeps the copy of the last launch.

## Make a test world

`makeTestWorld` makes one with a vanilla dedicated server: a superflat world of 1.20.5 (the oldest
release, so every release opens it), daylight and weather fixed, no mob spawns, a stone platform,
a chest, and a crafting table at the spawn; creative mode and commands on. Edit the console
commands in `gradle/test-world.gradle.kts` (`testWorldCommands`) for your checks. It also writes
`testworlds/hotbar.nbt` from `testHotbar` in the same file, with the data version of that release:
a later release upgrades the items.

```powershell
mise exec java@25 -- .\gradlew.bat makeTestWorld ["-Pname=flat"] ["-Pmc=1.20.5"] --console=plain --no-daemon
```

`testworlds/flat` comes from it. For a world with the blocks of the mod, make it by hand:

1. Start the oldest release that you test, with a window. A world opens on its own release and on
   later releases only (the game upgrades it, and an upgraded world does not go back).
   Example: `versionPlay "-Ploader=fabric" "-Pmc=1.20.5"`.
2. Create a world: creative mode, commands allowed, a superflat preset is good. Put the blocks
   and the items that your checks need near the spawn.
3. Close the world. Copy `<game directory>/saves/<World>` to `testworlds/<name>`. The game
   directory is printed by `versionPlay`, or is `mod/versions/<pin>/<loader>/build/client`.
4. Delete `session.lock` from the copy.
