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
| Create 6.0.10 | Integrated: hazards, filters (`#filter`), material lists, trains, elevators, moving platforms, stock keepers. Filter setting tested in game; trains, elevators and stock keepers built on Create's own client paths, not run in game. |
| EMI 1.1.24 (+ JEI) | Integrated: pick key, favorites, preferred recipes, recipe hints. Active in every pack test. |
| Sophisticated Storage / Backpacks | Integrated and tested (`sophisticated-fetch`). |
| Open Parties and Claims | Integrated (claims protection); active in tests; protection itself not triggered in a test (needs another player's claim). |
| Corpse | Integrated (`#corpse`); active in tests; not run (needs a death). |
| Lootr | Never deposited into. |
| Farmer's Delight | Modded crops and rich soil farmland in `#farm`; tomatoes left alone. |
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

## Not covered

* The Connector-loaded Fabric mods (Axiom, Iris Extension) were not part of the tests.
* Your real server's plugins, permissions and lag.
* Sable ships and aircraft.
