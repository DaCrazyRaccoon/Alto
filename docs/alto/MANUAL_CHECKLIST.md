# Things to try in your own game

Alto's automatic tests run in test worlds. This list is what only your own world, setup or server can show. Back up
first (see [INSTALL.md](INSTALL.md)) and try things in a copy of a world before your main one.

## First start

- [ ] The game starts with the Alto jar and no other Baritone jar. The mods list shows "Alto (unofficial Baritone fork)".
- [ ] Mods > Alto > Config opens the settings screen; searching for `altoClicksPerSecond` finds it.
- [ ] Controls > Alto: pick key (Y), stop key (End), HUD key (H), script runner key (not set). Rebind any that clash.
- [ ] Joining a server shows the reminder about automation rules once; joining again does not.

## Everyday jobs

- [ ] Holding only oak logs: `#sel 1`, `#sel 2`, `#sel fill oak_planks` fills the area; planks are crafted when needed.
- [ ] `#set alwaysUseCraftingStation true`, then the same: a crafting table is used, or placed and picked up again.
- [ ] `#craft chest 1`, `#craft furnace 1`, `#craft iron_ingot 4` (with raw iron and coal) work.
- [ ] Storage: `#home set`, select your storage room, `#home zone add storage`, `#home zone scan storage`;
      `#fetch cobblestone 10` brings exactly 10; `#deposit` puts the rest away (tools and food stay).
- [ ] A long `#mine`: Alto deposits when the inventory is full and the mining continues.
- [ ] A worn pickaxe gets replaced during mining (from storage or crafted).

## Mods

- [ ] `#goto` through your Create factory: no shaft, belt, funnel or track gets broken.
- [ ] Standing on one of your moving contraptions during a job: the bot stands still until it stops.
- [ ] Look at a deployer, `#filter iron_ingot`: its filter is set and the ingot stays in your inventory.
- [ ] Pick key: look at a funnel, press Y, open your inventory, hover an item in EMI, press Y: the filter is set.
- [ ] Running saws, drills and crushing wheels are walked around.
- [ ] `#fetch` from a Sophisticated Storage chest works; an upgrade in your hand is not applied.
- [ ] `#train <from> -> <to>` between two of your stations (needs a train that stops at both).
- [ ] `#elevator <floor>` in one of your elevators.
- [ ] `#order <item> <count>` at a stock keeper you are allowed to use.
- [ ] `#corpse` after a death.

## Travel

- [ ] Walk through a nether portal once; `#portals` lists it with its destination.
- [ ] From the nether: `#goto <x> <y> <z> overworld` arrives at the coordinates.
- [ ] `#boat <x> <z>` across a lake; `#minecart` on a rail line.

## Scripts

- [ ] Open `alto-script-editor.html`, load an example, save it to `.minecraft/baritone/alto/scripts/`.
- [ ] `#script run <name>` first shows what the script may do; `#script run <name> confirm` runs it; the HUD shows it.
- [ ] The stop key ends it at once.
- [ ] Quit while a script runs; after restarting, Alto asks; `#script resume` continues it.

## On a server

- [ ] Alto works on a server that does not have it (it is client-only).
- [ ] Container clicks look calm (about 4 per second); you are not kicked for spam.
- [ ] With high ping, Alto waits for the server instead of clicking ahead.
- [ ] Other players' claims (Open Parties and Claims) are never broken into.

## Safety

- [ ] With low health during a job, everything pauses with a message; `#resume` continues.
- [ ] `#stop` ends Baritone, Alto's job and a running script.
