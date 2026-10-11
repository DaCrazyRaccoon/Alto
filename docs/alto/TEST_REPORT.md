# Alto test report

Date: 2026-10-10. Fork commit: see the final section. Machine: Windows 11, JDK 21.0.12, NeoForge 21.1.249.

## 1. Automatic tests

| Suite | Result | How to run |
|---|---|---|
| Unit tests (planner, menu goals with a click simulator, pacing/backoff, storage index, portal routes, shopping list, script language core, the long mission script in a mock world, upstream Baritone tests) | **217/217 pass** | `gradlew test` |
| Block editor tests (validator, JSON round trips, schema, offline page, build, Java cross-check 176/176) | **207/207 pass** | `npm test` in `alto-editor/` |
| Client-only proof: a dedicated NeoForge server **without Alto** (mod list: Minecraft, NeoForge), the Alto client joins it, runs `#alto status`, `#plan`, `#alto integrations`, `#script list`, stays connected 20 s | **PASS** | `pwsh scripts/alto/server-join-test.ps1` |
| Launch check (game reaches the title screen; fails on error screens, crash reports, missing dependencies, hangs) | **PASS** (dev and pack copy) | `pwsh scripts/alto/test-client.ps1 -Run packClient -Mode boot` |

## 2. In-game self-tests

Every run starts a fresh test world, runs `#selftest all` and **fails unless every scenario passes**.

Scenarios (35 in the pack copy, 21 without mods): `planks`, `planks-table`, `craft-chest`, `smelt`, `fetch-build`,
`deposit-restock`, `shulker`, `auto-eat`, `tool-replace`, `integrations`, `useon`, `create-filter`*,
`sophisticated-fetch`*, `portal-travel`, `farm-seeds`, `minecart`, `boat`, `supply-build`, `portal-build`,
`script-long`, `safety-stop`, `screens`, `machines-safe`, `farm-modded`*, `lootr-skip`*, `create-materials`*,
`create-protect`*, `create-hazard`*, `create-platform`*, `create-elevator`*, `create-order`*, `create-train`*,
`emi-pick`*, `claims-protect`*, `corpse`*.
(* only registered when that mod is installed, i.e. in the pack copy.)

### The mod scenarios

Each one builds the real mod setup on the integrated server (like a creative player would) and then uses Alto's
normal commands:

| Scenario | Setup | Must happen |
|---|---|---|
| `machines-safe` | 2-high wall of barrels with a gap far to the side, diamond axe | Control run without `altoProtectMachines` breaks through; with it, the path goes around and no barrel breaks |
| `create-protect` | Wall of encased shafts and a row of train track, diamond pickaxe | Same, for Create blocks |
| `claims-protect` | Stone wall; the area is claimed by another player (Open Parties and Claims) | Control run before the claim breaks through; with the claim, it goes around |
| `create-hazard` | A mechanical drill turned by a creative motor, in the straight line | `#goto` stays 2 blocks away, no damage |
| `create-materials` | — | Farmland costs dirt, an encased shaft costs a shaft (Create's own rules) |
| `create-platform` | Mechanical piston platform with a creative motor; the player stands on it | Baritone stays paused while it moves (no walking off), then walks on from where it stopped |
| `create-elevator` | Elevator pulley, cab with contraption controls, two named floors | `#elevator Upper` scrolls the controls to the floor, right-clicks, rides 6 blocks up |
| `create-order` | Chest with iron, packager, stock link, stock ticker, a seated villager | `#order iron_ingot 4` opens the keeper, sends the order; the packager takes 4 ingots |
| `create-train` | 50-block track, stations AltoA and AltoB, a train with a conductor whose schedule waits for a player | `#train AltoA -> AltoB` walks to AltoA, boards a free seat, rides, gets off, walks to AltoB |
| `emi-pick` | A marked deployer, the inventory open | The pick key over an item in EMI's list sets the deployer's filter; EMI names "Pressing" for an iron sheet |
| `corpse` | Die 8 blocks away with logs, a pickaxe and bread | After respawning, `#corpse` takes all of it back |
| `farm-modded` | 8 ripe Farmer's Delight cabbages on rich soil farmland | `#farm` harvests and replants all 8 |
| `lootr-skip` | A Lootr loot chest next to you, a normal chest further away | `#deposit` puts everything into the normal chest |

RESULTS_TABLE

### The long mission script

`script-long` runs `long_mission.alto.json` (1,332 lines, 927 blocks and values; 5 event handlers, 5 procedures,
about 30 actions) from the scripts folder like a user script. It fetches logs from storage, crafts planks in a
loop with a safety break, crafts a chest (placing and picking up a crafting table), places and picks up a station by
hand, fetches raw iron and coal and smelts iron (crafting and placing a furnace), mines, builds a 3x3 wall row by row
through a procedure, tunes a note block with use-item-on-block, builds a `.nbt` schematic after a supply run, eats,
does math/logic/random/text checks, deposits what it mined, walks back, and waits for its timer handler, its chat
trigger (typed by the test) and its item-below handler (which crafts torches). It ends with result `ok` only if all
12 of its own checks pass.

## 3. What was found and fixed by the tests

* `#fetch` took whole stacks (M3) → exact counts; splitting a stack now takes the fewest clicks, so a container never
  looks emptied (you noticed this one).
* Natural blocks in test piles made `#mine` dig for natural andesite on real terrain → non-natural test blocks;
  dropped station items that fall away are no longer chased.
* A placed boat's item was not collected → explicit collection.
* Arriving through a portal: the portal cooldown keeps renewing while you stand in it → Alto steps out first.
* The builder only places from the hotbar → Alto moves blocks from the main inventory to the hotbar.
* Portal frames need a raised standing spot for the upper part → the bot towers up in front of the frame.
* Peaceful refills hunger by itself → disabled during the script test so `eat` really eats.
* A removed chest stayed in the storage index (Baritone only reports changes of blocks it tracks), so a deposit
  walked to a chest that was gone and waited there → Alto now forgets vanished containers every 2 s, and an approach
  that cannot reach its block gives up after 5 s and tries the next container.
* The build's up-front supply run and the builder's own supply could both start → the supply run goes first.
* Found only in the pack (first real-terrain final run, 20/22): a 2x2 craft finished in one click but its result was
  counted before the server's update arrived, so the craft step reported "missing ingredients" after succeeding, and
  that false failure put the builder's supply on cooldown for the next build → crafting now counts the real output;
  a new build resets the supply's failure counter.
* Found only in the pack: shift-clicked items land in the main inventory, but Baritone's farm (and the boat) only use
  the hotbar → seeds and boats are moved to the hotbar first.
* Found only in the pack: Baritone's loot-table lookup fails here, so `#mine <count>` never stopped (it mined 31 of
  8) → drops fall back to the block's item and Alto's drop table; the script `mine` block stops at its own count.
* Found by the mod tests (you reminded me about Create and the other mods):
  * Paths could break Create shafts, belts, funnels and train tracks when that was shorter → `altoProtectMachines`.
  * The moving-platform pause never switched on: Create's partial-tick helpers only move for rotating contraptions,
    and Baritone clears "who is in control" just before asking processes whether they are active → Alto tracks each
    contraption's movement itself and asks who was in control on the previous tick.
  * `#elevator` never reported arrival (the cab's motion value is stale on the client) and its time-out could not
    trigger → arrival is the cab standing still at the floor.
  * `#corpse` found no corpse: Corpse does not send what a corpse holds to clients, so all looked empty → Alto opens
    your nearest corpse and skips older empty ones.
  * `#train` tried to walk up onto the carriage to reach a seat → it walks to the ground within reach of the seat.

## 4. Not verified (and why)

* **Real multiplayer servers**: lag, anti-spam plugins and permissions were not tested on a real server (only the
  local server without Alto). Pacing (4 clicks/s), the lag-aware confirmation and backoff are unit tested.
* **Bigger Create setups**: railways with signals and several trains, stock networks that deliver packages by
  frogport, elevators with many floors. The tests use one of each (built by the test); the client paths are the same.
* **The EMI pick key over EMI's recipe screens** (the test uses EMI's item list next to the inventory; the recipe
  screen reads the real mouse position, which a test cannot move without moving your mouse).
* **Hostile mobs**: all test worlds are peaceful (Alto has no combat by design); the safety stop for mobs uses the same
  path as the tested low-health stop.
* **Sable ships**, and the Fabric mods that Connector loads (left out of the test copy, as you asked).
