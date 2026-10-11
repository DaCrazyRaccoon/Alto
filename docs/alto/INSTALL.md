# Installing, updating and removing Alto

**Alto is a vibecoded modification of Baritone. It is not made by the Baritone team, and the Baritone team does not
support it.**

Alto is one jar file, `baritone-alto-neoforge-1.21.1-<version>.jar`, for Minecraft 1.21.1 with NeoForge 21.1. It
replaces Baritone: never keep another Baritone jar next to it (they use the same mod id, `baritoe`).

## Before you install

Back up your game. In Prism Launcher: right-click the instance > **Copy Instance**. Or close the game and copy the
instance folder (at least `saves/`).

## Install

1. Close the game.
2. Open your `mods` folder (Prism Launcher: right-click the instance > **Folder** > `minecraft/mods/`).
3. Remove any other Baritone jar.
4. Copy the Alto jar into `mods`.
5. Start the game.

Alto runs on your client only. Do not put it on a server; servers do not need it. The first time you join a server,
Alto reminds you to check that server's rules on automation.

## Update

Replace the old Alto jar with the new one. Your settings (`baritone/settings.txt`) and Alto's data
(`baritone/alto/`) are kept.

## Remove

1. Close the game and delete the Alto jar from `mods`.
2. Optional: delete `baritone/alto/` (what Alto remembers about your storage, zones, portals, and your scripts) and the
   lines starting with `alto` in `baritone/settings.txt`.

Alto does not change your world files beyond what you see it do in the game.

## The script editor

`alto-script-editor.html` is a single file. Open it in any browser. It works offline and sends nothing anywhere.

## License

Alto is licensed under the LGPL-3.0, like Baritone. Using the jar yourself has no conditions. If you give the jar to
anyone else, you must also give them its complete source code. See [LICENSE-NOTE.md](LICENSE-NOTE.md).
