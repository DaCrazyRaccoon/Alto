# Alto developer notes

Alto is a private fork of Baritone 1.11.3 (branch `1.21.1`, tag commit `10e65932`) for NeoForge 21.1 / Minecraft
1.21.1. It adds container and GUI automation (crafting, smelting, storage, Create filters and more) to Baritone's
normal jobs. Like Baritone it is **client-only**: it registers no network channels and sends only the packets a
normal client sends.

## Build

```
set JAVA_HOME=<JDK 21>
gradlew build          # jar in build/libs, unit tests included
gradlew test           # unit tests only
```

The upstream build used unimined from `maven.wagyourtail.xyz`. That host is blocked on this machine, so the fork
builds with **ModDevGradle 2.0.148** (Gradle 9.2.1, NeoForge 21.1.249, Parchment 2024.11.17). The original
`buildSrc` is kept, unused, in `buildSrc.upstream-unimined/`.

Source sets:

| Source set | Contents |
|---|---|
| `src/api`, `src/main`, `src/launch` | Upstream Baritone plus Alto's loader-independent code (`baritone.alto.*`) |
| `neoforge/src/main` | NeoForge entry point, Alto's key mappings and the optional mod integrations |
| `src/test` | JUnit tests (upstream plus Alto's pure-Java tests) |
| `src/devtest` | Dev-only harness mod for automatic test runs. **Never part of the release jar.** |

## Layout of the Alto code

| Package | What it does | Minecraft classes? |
|---|---|---|
| `alto.core` | `ItemKey`, `ItemCounts`, `Pacer` (rate limits), `Backoff` | no |
| `alto.planner` | Recipe planner: inventory, shulkers, storage, crafting, smelting, mining | no |
| `alto.planner.mc` | Converts vanilla recipes and the world into the planner's model | yes |
| `alto.container` | `MenuView` (a snapshot of a menu) and `MenuGoals` (what to click next) | no |
| `alto.container.mc` | Reads real menus (`MenuReader`), clicks them (`ContainerSession`), adapters per menu type | yes |
| `alto.task` | Steps of a job (`CraftTask`, `SmeltTask`, `PlaceStationTask`, ...) run by `AltoProcess` | yes |
| `alto.station` | Finding, remembering and placing crafting tables/furnaces | yes |
| `alto.storage` | Homes, storage zones, the storage index, fetch/deposit/scan tasks | partly |
| `alto.supply` | Feeds `BuilderProcess` with materials it is missing | yes |
| `alto.upkeep` | Auto-eat, deposit, restock, compress, tool replacement | yes |
| `alto.hazard` | `hazards.json`: blocks to never walk into/on, running machines to avoid | partly |
| `alto.integration` | The soft-dependency framework (see below) | no |
| `alto.picker` | The pick key: item under the mouse, marked blocks | yes |
| `alto.script` | The Alto script language (loader, validator, interpreter) | core no |
| `alto.selftest` | `#selftest` scenarios (singleplayer test world) | yes |
| `alto.command` | Chat commands | yes |

The pure packages are unit tested without Minecraft (`src/test/java/baritone/alto/**`). `MenuSim` simulates
vanilla click rules so menu goals can be tested.

## Changes to upstream files

Every change is marked with a comment containing `ALTO FORK`. Search for it to see all of them:

| File | Change |
|---|---|
| `api/Settings.java` | One block of Alto settings between `ALTO FORK SETTINGS (begin)` and `(end)` |
| `Baritone.java` | Creates the `Alto` instance; `getAlto()` |
| `process/BuilderProcess.java` | Hands missing materials (or blocks only in the main inventory) to `BuilderSupply`; supply run on start; snapshot/resume helpers; remaining-material list |
| `pathing/movement/MovementHelper.java` and four movements | Modded ladders/scaffolding/farmland, hazard blocks, `avoidBreaking` also asks `AltoHooks.neverBreak` (machines, containers, tracks) |
| `utils/PathingControlManager.java` | `inControlLastTick()`: valid while processes are asked `isActive()` (used by the moving-platform pause) |
| `pathing/movement/CalculationContext.java` | `isPossiblyProtected` also asks `AltoHooks.isProtected` (claims) |
| `utils/pathing/Avoidance.java` | Extra avoidance areas from Alto (running machines) |
| `process/FarmProcess.java` | Modded crops and farmland, extra pickups, waits while Alto restocks seeds |
| `command/defaults/GotoCommand.java` | `goto <x> [y] <z> <dimension>` goes to `TravelCommands` (portal travel) |
| `command/defaults/ExecutionControlCommands.java` | `#resume` also ends an Alto safety stop; `#stop` also stops scripts and Alto's job |
| `utils/schematic/format/DefaultSchematicFormats.java` | `.nbt` structure files (`StructureNbtSchematic`) |
| `launch/BaritoneForgeModXD.java` | Calls `AltoNeoForge.init(modBus, container)` (integrations, keys, HUD, settings screen) |

`AltoHooks` is the only class upstream code calls. Its data is published from the client thread as immutable
snapshots, because the path search reads it on another thread.

## Processes and priorities

Alto runs its jobs in `AltoProcess`, a normal Baritone process:

* Standalone jobs (`#craft`, `#fetch`, `#corpse`, ...) are non-temporary.
* Jobs that supply another process (builder supply, upkeep) are **temporary** at priority -0.5, so the builder or
  miner keeps its state and continues afterwards.
* A finished job returns `DEFER`, never a pause, so the process below takes over in the same tick.

## Clicking containers

* All clicks go through `ContainerSession.send`, which uses `MultiPlayerGameMode.handleInventoryMouseClick` with
  a menu slot index. Alto never clicks by screen position.
* The `Pacer` limits clicks (default 4 per second), container opens and block uses.
* After each click the session waits for the server to confirm (ping and TPS based, `LagMonitor`). Three clicks
  without any visible effect stop the job (desync protection).
* Clicks never happen while the chat is open, while the player moves, or while sneaking.
* Containers are opened holding an empty hand, a block or a tool (`McItems.safeHotbarSlot`), because some modded
  blocks react to the held item.

## Adding support for another mod

1. Add the mod jar as `compileOnly` in `build.gradle` (from `testenv/pack/mods`), never as a runtime dependency.
2. Write `neoforge/src/main/java/baritone/alto/integration/<mod>/<Mod>Integration.java` implementing
   `AltoIntegration` (public no-argument constructor). Reference the mod's classes only from this package.
3. Register its class **name** for the mod id in `AltoNeoForge.INTEGRATIONS`. The class is loaded by name only
   when the mod is installed, so a missing mod can never cause a missing-class error. If `init` throws, the
   integration is switched off and listed by `#alto integrations`.
4. Add a setting switch in the Alto settings block, and add the mod to `M3Scenarios.INTEGRATIONS`.
5. What an integration can register:
   * a `ContainerAdapter` (`ContainerAdapters.register`) for its menus: slot roles, real stack counts, whether the
     container may be indexed or deposited into, and the block the menu belongs to;
   * storage blocks for zone scans (`alto.storage().addStorageBlocks`);
   * a `FilterAccess` for blocks with filter slots (`alto.addFilterAccess`);
   * hazard conditions (`alto.hazards()`), protection (`AltoHooks.publishProtection`);
   * pick-key providers (`alto.picker().addProvider`), recipe preferences and hints;
   * tick listeners and extra planner steps.
6. Add a self-test scenario that is registered only when the mod is loaded: register it from the integration's
   `init` with `alto.selfTests().register(...)` (see `CreateScenarios`, `ClaimsScenarios`, `CorpseScenarios`,
   `EmiScenarios`). The setup may build the mod's machines on the integrated server (it is a fixture, like a creative
   player); the behaviour under test must go through Alto's normal client paths. `M7Scenarios.WallDetour` is a
   ready-made "the pathfinder must not break this" fixture with a control run.

Rules for integrations: read what the mod already shows the client, and act only through normal input (clicks,
right-clicks, key presses the mod's own client handles). Never build a mod's packets by hand.

## Tests

| What | How |
|---|---|
| Unit tests | `gradlew test` |
| In-game scenarios | `#selftest list`, `#selftest <name>`, `#selftest all` (singleplayer, creates its own test area) |
| Automatic test client, dev (Baritone only: the soft-dependency test) | `pwsh scripts/alto/test-client.ps1 -Run client -Mode world -Commands "selftest all"` |
| Automatic test client, copy of the pack | `pwsh scripts/alto/test-client.ps1 -Run packClient -Mode world -Commands "selftest all" -TimeoutMinutes 30` |

The test client script fails on error screens, crash reports, missing dependencies, hangs (screenshots are taken
while it waits) and time-outs, and it only ever stops processes it started. Test runs use a separate worktree
(`work/test`) checked out at a commit, so editing sources cannot break a running test build.

The pack test copies only `mods`, `config`, `defaultconfigs` and `kubejs` from the Prism instance into
`testenv/pack` (never saves, accounts or launcher files). The Prism instance itself is never written to.
