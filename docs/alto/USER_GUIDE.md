# Alto user guide

Alto is a fork of Baritone for Minecraft 1.21.1 / NeoForge. On top of everything Baritone does (walking, mining,
building, farming), Alto can use chests, crafting tables, furnaces and modded containers like a player: it crafts,
smelts, fetches from and deposits into your storage, keeps your tools and food topped up, travels through portals
and by vehicle, works with Create, EMI and other mods, and runs scripts you make with the block editor.

Alto is **client-only**: install it in your own game only. Servers do not need it, and it sends only the packets a
normal client sends. Check a server's rules on automation before you use it there; Alto reminds you once per server.

Commands start with `#` (Baritone's prefix). `#help <command>` explains any command.

---

## 1. The headline: building with what you carry

Hold oak logs, select an area and fill it with oak planks:

```
#sel 1        (look at one corner)
#sel 2        (look at the other corner)
#sel fill oak_planks
```

When the builder runs out of planks, Alto crafts more from your logs (in the inventory 2x2 grid), puts them on the
hotbar and the build continues. With `#set alwaysUseCraftingStation true`, Alto uses a crafting table instead: one
nearby, one near your home, or it places one from your inventory (crafting it first if needed) and picks it up again
afterwards. This works the same for any block the planner can produce, and for `#build <schematic>`.

## 2. Crafting, smelting and planning

| Command | What it does |
|---|---|
| `#craft <item> [count]` | Gets `count` more of the item: from your inventory, carried shulker boxes, storage, crafting, smelting, and as a last resort mining. Stations are found, or placed and picked up again. |
| `#plan <item> [count]` | Shows the plan and its estimated time without doing it. Missing items are explained ("made by: Pressing (EMI)"). |
| `#materials [file] [fetch]` | Shopping list for the current build or a schematic file; `fetch` gets what fits from your storage. |

The planner prefers, in this order: what you carry, carried shulker boxes, storage within 48 blocks, then whatever
is cheapest of far storage / crafting / smelting, and mining last. It never uses items on the never-consume list
(diamonds, netherite and similar by default) and never burns valuable fuel. With EMI installed, the default recipes you
chose in EMI's recipe tree are preferred.

## 3. Homes, storage zones and the storage index

Alto only takes from and puts into containers **inside your storage zones** (unless `altoStorageAnywhere` is on).

```
#home set                      a home where you stand
#sel 1 / #sel 2                select your storage room
#home zone add storage         make the selection a storage zone called "storage"
#home zone scan storage        open every container once so Alto knows what is inside
#home zone rules storage cobblestone dirt    only these items are deposited there (optional)
```

Alto remembers what is in every container you or Alto open (per world, in `baritone/alto/worlds/`). It works with
chests, barrels, shulker boxes, hoppers, droppers, Sophisticated Storage chests/barrels/shulker boxes and placed
backpacks. Loot containers (Lootr) and corpses are never used as storage.

| Command | What it does |
|---|---|
| `#fetch <item> [count]` | Takes exactly that many from your storage. |
| `#deposit` | Puts everything except tools, food, armor and the keep list into your storage. |
| `#home storage [item]` | What is stored where. |
| `#shulker ...` | Carried shulker boxes: list, take from, put into. |

## 4. Automatic helpers while Baritone works

These run while Baritone mines, builds, farms or explores, pause the job, and let it continue afterwards.

| Helper | Default | Setting |
|---|---|---|
| Deposit when the inventory is nearly full | on (only with a storage zone) | `altoAutoDeposit` |
| Restock torches, food... from storage | on (only with a storage zone) | `altoRestock`, `altoRestockItems` |
| Replace nearly broken tools (from storage or crafted) | on | `altoToolUpkeep` |
| Eat when hungry | off | `altoAutoEat` |
| Compress items (9 ingots into a block...) when the inventory is full | off | `altoCompress` |

## 5. Travel

| Command | What it does |
|---|---|
| `#goto <x> [y] <z> <overworld|nether>` or `#travel <dimension> <x> [y] <z>` | Through the nether portal that means the least walking, then on to the exact coordinates. Alto learns portals you come near and remembers where each one led. With `altoBuildPortals` on (off by default) it builds and lights a portal when none is known (needs 10 obsidian, a flint and steel, and some cobblestone or similar). |
| `#portals [forget]` | The portals Alto knows in this world. |
| `#minecart [x z]` | Rides the nearest free minecart. |
| `#boat <x> <z>` | Crosses water by boat (uses a boat nearby or places yours, picks it up again). Straight lines only. |
| `#train <from> -> <to>`, `#stations` | Create trains between named stations: waits for a train, sits on a free passenger seat, gets off at the destination. Never touches controls, conductors or schedules. |
| `#elevator <floor>` | Create elevators: selects the floor on the cab's controls and rides. |

While you stand on a moving Create contraption (elevator, piston platform, bearing...), Baritone stops walking until
it stops.

## 6. Farming

`#farm [range]` (Baritone) also harvests and replants modded crops (Farmer's Delight cabbages, onions...) on modded
farmland. Crops you harvest by right-clicking (tomatoes) are left alone. When you run out of seeds and there is empty
farmland, Alto fetches seeds from your storage.

## 7. Create, EMI and other mods

* **Filters:** `#filter <item|picked|hand|clear>` while looking at a Create deployer, funnel, tunnel or chute sets its
  filter (plain items are not used up). `#filter <item> <x y z> [face]`, `#filter <item> marked`, `#filter <item> sel`.
* **Pick key (default Y):** in the world it marks/unmarks the block you look at; in EMI or any inventory it picks the
  item under the mouse. With marked blocks, the picked item becomes their filter; otherwise Alto offers actions in chat
  (filter / fetch / craft / plan / mine). `#pick favorites` lists your EMI favorites.
* `#useon <item> <x y z> [face]` uses an item on a block (e.g. puts an item on a depot).
* `#order <item> <count> [address]` orders from the nearest Create stock keeper (only when the server lets you open it).
* `#corpse` (Corpse mod) walks to your corpse and takes your items back.
* Open Parties and Claims: Baritone never breaks or places in other players' claims.
* Hazards (running saws, drills, crushing wheels, fans, belts...) are avoided; edit `baritone/alto/hazards.json` and
  `#alto hazards` to reload.
* `#alto integrations` shows which mod integrations are active.

## 8. Scripts

Make scripts with the block editor `alto-script-editor.html` (open it in any browser; it works offline). Save them as
`<name>.alto.json` into `.minecraft/baritone/alto/scripts/` (the editor's Save button can do that), then:

```
#script list
#script check <name>          what it may do, warnings
#script run <name>            asks once for new or changed scripts: #script run <name> confirm
#script pause / resume / stop / status
#script gui                   the script runner screen (also a key under Controls > Baritone (Alto))
```

Scripts never start by themselves. A running script is saved every few seconds; after a restart Alto asks before it
continues (`#script resume`). Chat triggers in scripts only react to text **you** type. Scripts cannot send chat or
run Baritone commands unless you allow it (`altoScriptChat`, `altoScriptCommands`). The language is described in
`SCRIPT_LANGUAGE.md`; `examples/` has example scripts.

## 9. Safety, stopping, and the HUD

* **Stop key (default End):** stops the script, Alto's job and Baritone. `#stop` does the same.
* **Safety stops (on):** when your health drops to 4 hearts (`altoSafetyHealth`) or a hostile mob is within 6 blocks,
  everything pauses (Alto has no combat). `#resume` continues.
* **Progress HUD (key H, `#alto hud`):** what the script, Alto and Baritone are doing.

## 10. Settings

`#alto settings`, or Mods > Baritone (Alto fork) > Config: every Alto setting in groups with a search box and a reset
button, plus homes and storage zones. `#set <setting> <value>` works too. Pacing settings (`altoClicksPerSecond`,
default 4) keep Alto gentle on servers; there is no "random human-like jitter".

## 11. Self-tests

`#selftest list` / `#selftest <name>` / `#selftest all` run Alto's own scenarios. They build test setups and change
your inventory, so they only run in singleplayer worlds named `alto_test...` (or with `confirm`).

## 12. Where Alto keeps its files

`.minecraft/baritone/alto/`: `worlds/<world>/` (storage index, zones, portals), `scripts/`, `hazards.json`,
`shopping-list.txt`, `selftest/`. Deleting the folder resets Alto's memory; your worlds are not touched.
