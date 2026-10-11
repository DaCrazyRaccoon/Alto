# Manual checklist

Things to try yourself in your real game (after backing up, see `INSTALL.md`). The automatic tests cover most
features in test worlds; this list is about what only a real world, a real server or your own setup can show.
Tick each line when it behaved as described.

## First start

- [ ] The game starts with the Alto jar and no other Baritone jar. Mods list shows "Alto (unofficial Baritone fork)".
- [ ] Mods > Alto (unofficial Baritone fork) > Config opens the settings screen; search finds `altoClicksPerSecond`.
- [ ] Controls > Alto: pick key (Y), stop key (End), HUD key (H), script runner key (unbound). No clash
      with keys you use (rebind if needed).
- [ ] Joining a server shows the one-time reminder about automation rules; joining again does not.

## Everyday jobs (singleplayer copy of a world first)

- [ ] Holding only oak logs: `#sel 1`, `#sel 2`, `#sel fill oak_planks` fills the area; planks are crafted when needed.
- [ ] Same with `#set alwaysUseCraftingStation true`: a crafting table is used (or placed and picked up again).
- [ ] `#craft chest 1`, `#craft furnace 1`, `#craft iron_ingot 4` (with raw iron and coal) work.
- [ ] Storage: `#home set`, select your storage room, `#home zone add storage`, `#home zone scan storage`;
      `#fetch cobblestone 10` brings exactly 10; `#deposit` puts the rest away (tools and food stay).
- [ ] A long `#mine` run: Alto deposits when the inventory is full and the mining continues.
- [ ] A worn pickaxe gets replaced during mining (from storage or crafted).

## Mods

All of these passed in the test worlds with real mod blocks (see `TEST_REPORT.md`); your own builds are bigger and
belong to your world, so they are still worth one try each.

- [ ] Walking through your Create factory with `#goto`: no shaft, belt, funnel or track is broken (paths go around).
- [ ] Standing on one of your moving contraptions during a job: the bot stands still until it stops.
- [ ] Create: look at a deployer, `#filter iron_ingot` sets its filter; the ingot stays in your inventory.
- [ ] Pick key: look at a funnel, press Y (marked), open your inventory, hover an item in EMI, press Y: the filter
      is set.
- [ ] Running saws / drills / crushing wheels are walked around, not into.
- [ ] Sophisticated chest: `#fetch` from it works; holding an upgrade item while Alto opens it does not apply it.
- [ ] `#train <from> -> <to>` between two of your Create stations (needs a train that stops at both).
- [ ] `#elevator <floor>` in one of your Create elevators.
- [ ] `#order <item> <count>` at your stock keeper (only works where you may use it).
- [ ] `#corpse` after a death (Corpse mod).

## Travel

- [ ] Walk through a nether portal once; `#portals` lists it with its destination.
- [ ] From the nether: `#goto <x> <y> <z> overworld` arrives at the coordinates.
- [ ] `#boat <x> <z>` across a lake; `#minecart` on a rail line.

## Scripts

- [ ] Open `alto-script-editor.html`, load an example, save it to `.minecraft/baritone/alto/scripts/`.
- [ ] `#script run <name>` first shows what the script may do; `#script run <name> confirm` runs it; the HUD shows it.
- [ ] The stop key ends it at once.
- [ ] Quit while a script runs; after restarting, Alto asks; `#script resume` continues it.

## Multiplayer server (your server)

- [ ] Alto works on a server that does not have it (it is client-only).
- [ ] Container clicks look calm (about 4 per second); no kicks for spam.
- [ ] With high ping, Alto waits for the server instead of clicking ahead.
- [ ] Claims of other players (Open Parties and Claims) are never broken into.

## Safety

- [ ] With low health during a job, everything pauses with a message; `#resume` continues.
- [ ] `#stop` ends Baritone, Alto's job and a running script.
