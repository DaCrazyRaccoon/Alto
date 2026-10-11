# Mod compatibility

Alto was developed and tested with the **Createrington Rails 'n Sails** modpack: Minecraft 1.21.1, NeoForge 21.1.249,
about 280 mod jars (350 mods with the ones they contain), including Create 6.0.10 and EMI 1.1.24. The tests ran in a
copy of the pack, never in a real game installation.

All mod support is optional. Alto starts without these mods, and with other versions of them; if a version does not
fit, only Alto's support for that mod switches itself off.

## Mods Alto works with

| Mod | What Alto does with it | Tested in game |
|---|---|---|
| Create 6.0.10 | Trains, elevators, moving platforms, stock keepers, filters, material lists; walks around running machines; never breaks shafts, belts or tracks for a path | yes, with real Create machines built by the tests |
| Steam 'n' Rails and other Create add-ons | Their tracks and machines are protected from paths like Create's | through the same protection rules |
| EMI 1.1.24 | Pick key, favorites, preferred recipes, recipe hints | yes |
| Sophisticated Storage and Backpacks | Chests, barrels and placed backpacks as storage; upgrades you hold are never applied | yes |
| Open Parties and Claims | No breaking or placing in other players' claims | yes |
| Corpse | `#corpse` takes your items back | yes |
| Farmer's Delight | Crops and rich soil farmland in `#farm`; tomatoes left alone | yes |
| Lootr | Never used to store your items | yes |

## Other mods in the pack

| Mod | Notes |
|---|---|
| Inventory Essentials | Works alongside: Alto re-reads the inventory after every action. |
| Carry On | Alto never sneak-right-clicks, so it never picks up containers. |
| Sophisticated Backpacks upgrades (pickup, magnet, feeding...) | They may move items while Alto works; Alto re-reads the inventory and copes. |
| Sable / Create Aeronautics | No support. Baritone may find poor paths near physics ships. |
| Freecam | While Freecam is on, it blocks movement and block use, so Alto cannot act. |
| Fabric mods loaded by Sinytra Connector (Axiom, Iris Extension) | Not tested. |
| Other Baritone or automation mods | None in the pack. Never install another Baritone next to Alto. |

## Mixins

Alto adds no mixins beyond Baritone's own. No mod in the pack conflicts with Baritone's mixins. Alto clicks in
containers by slot number, not by screen position, so mods that change how screens look do not affect it.

## Not tested

- Real multiplayer servers (plugins, permissions, lag).
- Sable ships and aircraft.
- Large Create networks: many trains and signals, stock networks with package deliveries, elevators with many floors.
  The tests use one of each.
