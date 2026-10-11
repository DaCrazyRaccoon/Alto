# Alto changelog

Alto is a modified version of Baritone 1.11.3 (branch `1.21.1`, commit `10e65932`) for Minecraft 1.21.1 / NeoForge
21.1.249. Versions are `1.11.3-alto.<milestone>`.

## 1.0.0 — M6: settings, safety, HUD, scripts in game, final tests

* Settings screen (Mods list > Config, `#alto settings`): groups, search, reset per setting, homes and storage zones.
* Safety stops: low health or a hostile mob nearby pauses everything; `#resume` continues. `#stop` also ends scripts.
* Progress HUD (key H, `#alto hud`). Global stop key (End).
* Script runner screen (`#script gui`, key) that follows the scripts folder; first-run confirmation dialog.
* Builder: blocks that are only in the main inventory are moved to the hotbar by Alto (paced) instead of the bot
  waiting at the build (upstream Baritone only places from the hotbar).
* Portal building (`altoBuildPortals`, off by default): 10 obsidian, corner blocks, lit with flint and steel.
* Exact takes use the fewest clicks (pick up half, or put the smaller part back), so a container never looks emptied
  while Alto splits a stack.
* Tests: real-terrain test worlds, a run fails unless every self-test passes, dedicated-server join test (a server
  without Alto), the long mission script.

## 0.5.0 — M5: scripts

* `#script` runs Alto scripts (`baritone/alto/scripts/*.alto.json`): every action block is a real Alto job or Baritone
  process; queries read the world; chat triggers react only to text you type; runs are saved and continue after a
  restart only when you say so; command and chat blocks are refused unless you allow them.
* Offline block editor `alto-script-editor.html` (Blockly, single file, no network) with item/block pickers fed by
  `#export-registry`; JavaScript validator identical to Alto's (checked against the Java code).

## 0.4.0 — M4: travel, vehicles, farming, supply runs

* Portals: learned automatically; `#goto x y z <dimension>`, `#travel`, `#portals`.
* `#minecart`, `#boat`; Create `#train`, `#stations`, `#elevator`; pause on moving Create contraptions.
* `#farm`: modded crops and farmland, seed restock from storage.
* `.nbt` structure schematics (Create, structure blocks) for `#build`; material lists and supply runs; `#materials`.
* Create stock keepers: `#order`.
* `#fetch` takes exactly the typed count.

## 0.3.0 — M3: Create, EMI and other mods

* Soft dependencies loaded only when the mod is installed; `#alto integrations`.
* Create: hazards, `#filter`, `#useon`. EMI: pick key, favorites, preferred recipes, recipe hints.
* Sophisticated Storage / Backpacks, Open Parties and Claims, Corpse (`#corpse`), Lootr.
* Containers are opened with a safe item in hand. Stations near your home are remembered.

## 0.2.0 — M2: furnaces, storage, upkeep

* Furnaces, smokers, blast furnaces. Homes, storage zones, the storage index, `#fetch`, `#deposit`, zone scans.
* Auto-deposit, restock, tool replacement, compress, auto-eat. Carried shulker boxes as a source.

## 0.1.0 — M1: containers, planner, stations, builder supply

* Container layer with pacing, lag-aware confirmation and desync protection; recipe-book placement.
* Planner (inventory, storage, crafting, smelting, mining), `#craft`, `#plan`.
* Crafting table found, or placed and picked up; `alwaysUseCraftingStation`.
* The builder gets missing blocks crafted and continues.
* Build moved from unimined to ModDevGradle (the unimined host is blocked on the build machine).
