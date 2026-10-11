# Test report

**Alto is a vibecoded modification of Baritone. It is not made by the Baritone team, and the Baritone team does not
support it.** These tests are Alto's own; they say nothing about Baritone itself.

Version 1.1.0. Windows 11, JDK 21.0.12, NeoForge 21.1.249.

## How Alto is tested

- **Unit tests** (`gradlew test`): the recipe planner, container clicking (with a click simulator), pacing, the
  storage index, portal routes, the script language, and Baritone's own tests.
- **Script editor tests** (`npm test` in `alto-editor/`): the editor checks scripts exactly like Alto does.
- **In-game self-tests** (`#selftest all`): each scenario builds its own test setup in a fresh world and runs Alto's
  normal commands, the same way a player would. A run fails unless every scenario passes. The mod scenarios build
  real mod machines first: a Create train with stations and a conductor, an elevator, a piston platform, a stock
  keeper network, another player's claim, a death.
- **Client-only check**: a dedicated server without Alto; the Alto client joins it and uses Alto there.

The final runs use real (not flat) overworlds, in a copy of the Createrington Rails 'n Sails modpack, in a game
without any other mod, and in a game with only some of the supported mods.

RESULTS

## Scenarios

| Scenario | What must happen |
|---|---|
| `planks`, `planks-table` | Fill an area with planks holding only logs (2x2 grid; crafting table placed and picked up) |
| `craft-chest`, `smelt` | Craft a chest from logs; smelt iron in a furnace that is placed and picked up |
| `fetch-build`, `deposit-restock`, `shulker` | Build from storage; deposit and restock; build from a carried shulker box |
| `auto-eat`, `tool-replace` | Eat when hungry; replace a nearly broken pickaxe |
| `integrations`, `useon` | Every installed mod's support is on and no other; use an item on a block |
| `portal-travel`, `portal-build` | Travel through the nether; build and light a portal |
| `farm-seeds`, `minecart`, `boat`, `supply-build` | Farm with seeds from storage; ride a minecart; cross water by boat; supply a build |
| `script-long` | A 1,300-line script with events, loops, procedures and 12 checks of its own |
| `safety-stop`, `screens` | Low health pauses everything; the settings and script screens open |
| `machines-safe` | Paths go around barrels instead of breaking them (a control run without the protection breaks through) |
| `create-filter`, `create-materials`, `create-hazard`, `create-protect` | Deployer filter; Create's material costs; keep away from a running drill; never break shafts or tracks |
| `create-platform`, `create-elevator`, `create-order`, `create-train` | Ride a piston platform; ride an elevator; order from a stock keeper; ride a train |
| `sophisticated-fetch`, `emi-pick`, `claims-protect`, `corpse` | Sophisticated Storage; EMI pick key; Open Parties and Claims; Corpse |
| `farm-modded`, `lootr-skip` | Farmer's Delight crops; Lootr chests are never used for storage |

Mod scenarios are only registered when that mod is installed.

## Problems the tests found (all fixed)

- Fetching took whole stacks; now it takes exact counts, splitting a stack with as few clicks as possible.
- The builder only places blocks from the hotbar; Alto now moves the blocks there first. The same for seeds and boats.
- A chest that was removed stayed in Alto's storage list; vanished containers are now forgotten every 2 seconds.
- A craft could be counted before the server confirmed it, or its result read before the server updated it; Alto now
  waits for the server.
- Baritone's loot lookup fails in large modpacks, so `#mine <count>` never stopped; Alto now knows what blocks drop.
- Paths could break Create shafts, belts and train tracks; now they never do (`altoProtectMachines`).
- The pause on moving contraptions never switched on, `#elevator` never noticed it had arrived, `#corpse` found no
  corpse on servers, and `#train` tried to climb onto the carriage; all fixed.
- Crafting by hand used three clicks per grid slot; now one.

## Not tested

- Real multiplayer servers: lag, anti-spam plugins, permissions. Pacing and waiting for the server are unit tested.
- Large Create setups: many trains and signals, stock networks that deliver packages, elevators with many floors.
- The EMI pick key over EMI's recipe screens (a test cannot move the real mouse).
- Hostile mobs: test worlds are peaceful. The mob safety stop uses the same path as the tested low-health stop.
- Sable ships, and Fabric mods loaded through Sinytra Connector.
