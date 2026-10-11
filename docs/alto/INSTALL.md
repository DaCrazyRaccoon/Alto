# Installing, updating and removing Alto

Alto is one jar: `baritone-alto-neoforge-1.21.1-1.11.3-alto.<version>.jar`. It replaces Baritone; never keep another
Baritone jar next to it (both use the mod id `baritoe`).

## Before you install: back up

1. In Prism Launcher, right-click the instance > **Copy Instance**, or close the game and zip the instance folder.
2. If you only want to protect your worlds: copy `minecraft/saves/` somewhere safe.

## Install (Prism Launcher)

1. Close the game.
2. Right-click the instance > **Folder**, open `minecraft/mods/`.
3. Remove any other Baritone jar.
4. Copy the Alto jar into `mods/`.
5. Start the game. The first time you join a server, Alto shows a one-time reminder to check the server's rules on
   automation.

Alto is client-only: do not put it on a server (it would not load there, and servers do not need it).

## Update

Replace the old Alto jar with the new one. Your settings (`minecraft/baritone/settings.txt`) and Alto's data
(`minecraft/baritone/alto/`) are kept.

## Remove

1. Close the game and delete the Alto jar from `mods/`.
2. Optional: delete `minecraft/baritone/alto/` (Alto's memory of storage, zones, portals and your scripts) and the
   `alto...` lines in `minecraft/baritone/settings.txt`.

Alto never changes your worlds' files beyond what you see it do in the game.

## The script editor

`alto-script-editor.html` is a single file; open it in any browser. It works offline and sends nothing anywhere.

## License (please read if you share the jar)

Baritone, and therefore Alto, is licensed under the **GNU Lesser General Public License v3.0** (LGPL-3.0); see
`LICENSE` and `LICENSE-NOTE.md`. Using the jar yourself has no conditions. **If you give the jar to anyone else**
(a friend, a modpack, a download), you must also give them the complete source code of the version you give them
(the `source` folder, or an archive of the git repository), under the same license, and keep the license and
copyright notices. Details: `LICENSE-NOTE.md`.
