# Pack compatibility report: Createrington Rails 'n Sails

Pack: Minecraft 1.21.1, NeoForge 21.1.249, 278 jars / 310 mod ids (352 mods loaded including nested ones),
Create 6.0.10, EMI 1.1.24, JEI 19.57. Full static scan (mod list, every mixin, KubeJS recipe changes):
`research/pack-scan.md`, `research/pack-mods.tsv`, `research/pack-mixins.tsv`.

## How it was tested

* Only a **copy** of the pack's `mods`, `config`, `defaultconfigs` and `kubejs` folders was used (`testenv/pack`),
  in a separate game folder. The Prism instance was never written to (see the audit).
* As you asked, the Fabric-only jars that Sinytra Connector loads (Axiom, Iris Extension) were left out of the test
  copy, together with Connector itself and Continuity. In your real game they are present; see "Not covered" below.
* The pack starts with Alto in about 30-70 s to the title screen; the automatic launch check fails on any error
  screen, crash report or missing dependency.
* All self-test scenarios run in the pack copy (superflat and real terrain); results are in `TEST_REPORT.md`.

## Mod by mod

| Mod | Status with Alto |
|---|---|
| Create 6.0.10 | Integrated and **tested in game with real Create machines** built by the tests: filters (`create-filter`), material lists (`create-materials`), running machines avoided (`create-hazard`), shafts/belts/tracks never broken by paths (`create-protect`), riding a piston platform (`create-platform`), `#elevator` in an elevator with contraption controls (`create-elevator`), `#order` at a stock keeper with a packager network (`create-order`), `#train` on a railway with two stations and a scheduled train with a conductor (`create-train`). |
| Steam 'n' Rails and other Create add-ons | Their tracks (all gauges, crossings) and every block with a block entity are protected from paths. No add-on changes the client paths Alto uses (checked in the static scan). |
| EMI 1.1.24 (+ JEI) | Integrated: pick key, favorites, preferred recipes, recipe hints. Tested: the pick key over EMI's item list sets a marked deployer's filter, and missing items get EMI's categories as hints (`emi-pick`). |
| Sophisticated Storage / Backpacks | Integrated and tested (`sophisticated-fetch`). |
| Open Parties and Claims | Integrated and tested: a claim of another player over the test area makes paths go around instead of breaking (`claims-protect`). Alto sees the claims your server sends to clients (`claimsSynchronization`). |
| Corpse | Integrated and tested: die, respawn, `#corpse` takes everything back (`corpse`). |
| Lootr | Tested: never deposited into, even when it is the closest container (`lootr-skip`). |
| Farmer's Delight | Tested: ripe cabbages on rich soil farmland are harvested and replanted (`farm-modded`); tomatoes left alone. |
| Inventory Essentials (stack/tool refill on) | Works alongside: Alto re-reads the inventory after every action. |
| Carry On | Alto never sneak-right-clicks, so it never picks up containers. |
| Sophisticated Backpacks upgrades (pickup, magnet, tool swapper, feeding...) | They may move items while Alto works; Alto re-reads the inventory and tolerates it. |
| Sable / Create Aeronautics | Not integrated (you chose not to). Baritone may path poorly near physics ships; the static scan lists Sable's movement and ray-cast hooks as the highest risk. |
| Freecam | When Freecam is on, it blocks block use and movement; Alto then cannot act (expected). |
| Axiom (Fabric, via Connector) | Not in the test copy. Its key hooks only act while its editor is open. |
| No other Baritone or automation mod | Confirmed by a byte search of every jar. |

## Mixins

Alto adds no new mixins to the shipped jar beyond upstream Baritone's (the test harness's own mixin is dev-only and
not in the jar). No pack mixin duplicates one of Baritone's `@Redirect`s or its `@Overwrite`; the pack starts cleanly
with the fork in every run. Container automation clicks through `MultiPlayerGameMode` by slot index, which avoids the
screen-level click hooks of Create's deployer, FancyMenu and LDLib2.

## Bugs the mod tests found (fixed)

* The moving-platform pause never switched on (two separate reasons), so Baritone kept walking on moving pistons and
  elevators.
* `#elevator` never reported arrival; its time-out could not trigger.
* `#corpse` could not see any corpse on a server (Corpse does not send corpse contents to clients).
* `#train` tried to walk up onto the carriage to reach a seat.
* Paths could break Create shafts, belts, funnels and train tracks (now protected by default).

## Not covered

* The Connector-loaded Fabric mods (Axiom, Iris Extension) were not part of the tests.
* Your real server's plugins, permissions and lag.
* Sable ships and aircraft.
* Long train networks with signals and several trains, and stock networks with frogports and deliveries (the tests
  use one train and one packager; the logic is the same).
