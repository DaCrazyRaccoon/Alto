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

Scenarios (22): `planks`, `planks-table`, `craft-chest`, `smelt`, `fetch-build`, `deposit-restock`, `shulker`,
`auto-eat`, `tool-replace`, `integrations`, `useon`, `create-filter`*, `sophisticated-fetch`*, `portal-travel`,
`farm-seeds`, `minecart`, `boat`, `supply-build`, `portal-build`, `script-long`, `safety-stop`, `screens`.
(* only where Create / Sophisticated Storage are installed, i.e. in the pack copy.)

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

## 4. Not verified (and why)

* **Real multiplayer servers**: lag, anti-spam plugins and permissions were not tested on a real server (only the
  local server without Alto). Pacing (4 clicks/s), the lag-aware confirmation and backoff are unit tested.
* **Create trains, elevators, stock keepers, moving platforms**: the test world cannot assemble a train, an elevator
  or a stock network by itself. These use only Create's normal client paths (checked against Create 6.0.10's source)
  and compile against your pack's Create jar. Please try them (`MANUAL_CHECKLIST.md`).
* **Open Parties and Claims protection** (needs another player's claim), **Corpse recovery** (needs a death), **EMI
  pick key over EMI's own recipe screens** (needs real mouse input).
* **Hostile mobs**: all test worlds are peaceful (Alto has no combat by design); the safety stop for mobs uses the same
  path as the tested low-health stop.
* **Sable ships**, and the Fabric mods that Connector loads (left out of the test copy, as you asked).
