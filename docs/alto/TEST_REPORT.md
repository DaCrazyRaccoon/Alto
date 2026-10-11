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

## Results (version 1.1.0)

| Run | Result |
|---|---|
| Copy of the Createrington Rails 'n Sails modpack, real overworld (seed 5964811878973892834) | **35/35 scenarios pass** |
| No other mods, real overworld (seed 6235065894245246003) | **21/21 pass** |
| Only EMI, Corpse and Open Parties and Claims, no Create (seed -3043394739340678893) | **23/23 pass** (active [EMI, Open Parties and Claims, Corpse]) |
| Dedicated server without Alto; the Alto client joins and uses Alto | **PASS** |
| Unit tests | **219/219 pass** |
| Script editor tests | **207/207 pass** |

### Every scenario of the modpack run

| Scenario | Result | What happened |
|---|---|---|
| `planks` | PASS | 9/9 planks placed; left over: 3 planks, 0 logs; tables in world 0, in inventory 0 |
| `planks-table` | PASS | 9/9 planks placed; left over: 3 planks, 0 logs; tables in world 0, in inventory 1 |
| `craft-chest` | PASS | chests 1, tables in world 0, table items 1 |
| `smelt` | PASS | ingots 4, furnace items 1, furnace blocks 0, raw iron left 0 |
| `fetch-build` | PASS | 9/9 placed; logs left in the chest 0; index knows 1 container(s) |
| `deposit-restock` | PASS | cobblestone deposited (chest A 1728, chest B 448), torches restocked 32, tools/food kept true |
| `shulker` | PASS | 9/9 placed; shulker boxes carried 1 (logs inside 0), in world 0 |
| `auto-eat` | PASS | ate 1 bread, hunger now 19 |
| `tool-replace` | PASS | now carrying 2 stone pickaxes; table items 1 |
| `integrations` | PASS | active [Corpse, Sophisticated Storage/Backpacks, Open Parties and Claims, EMI, Create] |
| `useon` | PASS | note 1, dirt 16 |
| `create-filter` | PASS | deployer filter {count:1,id:"minecraft:iron_ingot"}, ingots in inventory 1 |
| `sophisticated-fetch` | PASS | logs 8, chest block sophisticatedstorage:chest, tier upgrade still carried 1 |
| `portal-travel` | PASS | back in minecraft:overworld, 0 blocks from the target, portal links known 2; done: Travel to -81 63 -121 in the overworld: arrived |
| `farm-seeds` | PASS | planted 8/8 after fetching seeds; seeds left in the chest 0 |
| `minecart` | PASS | boarded true, riding now false, carried 13 blocks; done: Ride a minecart: got off at -79, 63, -113 |
| `boat` | PASS | boarded true, at target true, boat items 1, boats left in the world 0; done: Boat towards -81 -113: arrived |
| `supply-build` | PASS | stone bricks 9/9, glass 9/9, shopping list 2 material(s), 0 missing |
| `portal-build` | PASS | reached the nether true, obsidian left 0; done: Travel to -11 -16 in the nether: arrived |
| `script-long` | PASS | status FINISHED, result ok, checks 12/12, actions 21, wall 9/9, glass 9/9, note 1, pings typed 1 |
| `safety-stop` | PASS | stopped at 1 mined, paused 5 s, resumed and mined 8/8 |
| `screens` | PASS | screens [AltoSettingsScreen, ScriptRunnerScreen], HUD [§eBaritone§7: Custom Goal GoalBlock{x=-81,y=63,z=175}, §8goal GoalBlock{x=-81,y=63,z=175}] |
| `machines-safe` | PASS | went around, 20/20 blocks intact; without the protection it broke 1 block(s) |
| `farm-modded` | PASS | harvested 8 cabbages, replanted 8/8 (0 still ripe) |
| `lootr-skip` | PASS | done: Deposit items: stored 16 items; cobblestone carried 0, in the normal chest 16/16; indexed [minecraft:chest] |
| `corpse` | PASS | done: Recover your items from your corpse: all items recovered (19); logs 12/12, pickaxe 1/1, bread 6/6 |
| `claims-protect` | PASS | went around, 20/20 blocks intact; without the protection it broke 2 block(s) |
| `emi-pick` | PASS | EMI item minecraft:oak_log at (317,20), picked minecraft:oak_log, done: Filters: set the filter to oak_log on 1 block(s): all 1 steps done, deployer f |
| `create-materials` | PASS | farmland -> 1x dirt, encased shaft -> 1x create:shaft, list for 3 farmland + 2 encased shafts: dirt 3, shaft 2 |
| `create-protect` | PASS | went around, 30/30 blocks intact; without the protection it broke 2 block(s) |
| `create-hazard` | PASS | closest distance to the running drill 2.09 blocks, no damage |
| `create-platform` | PASS | rode 101 ticks, Baritone paused 100 of them, drift on the platform 0.00 blocks, platform moved from x-2 to x+1, then reached the goal |
| `create-elevator` | PASS | done: Elevator to Upper: arrived at Upper; rose 6 blocks, paused while moving 126 ticks |
| `create-order` | PASS | done: Order 4x iron_ingot: ordered 4x iron_ingot; chest 32 -> 28 iron, packager holds Cardboard Package |
| `create-train` | PASS | done: Train from AltoA to AltoB: arrived at AltoB; boarded true, rode to x+41, 3.3 blocks from AltoB |

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
