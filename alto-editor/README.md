# Alto Script Editor

A block editor for Alto scripts (`*.alto.json`). It is one HTML file that works offline: no server, no internet, no
installation. The script language is described in [`docs/alto/SCRIPT_LANGUAGE.md`](../docs/alto/SCRIPT_LANGUAGE.md).

![The script editor](../docs/images/editor.jpg)

## Use it

1. Open **`dist/alto-script-editor.html`** in a browser (double-click it). Chrome and Edge can save straight into
   Alto's scripts folder; Firefox and Safari download the file instead.
2. Drag blocks from the categories on the left. **Search blocks** finds a block by name. Blocks only fit where their
   type fits.
3. The **Problems** tab checks the script the same way Alto does and shows what the script is allowed to do. Click a
   problem to jump to its block.
4. **Save to scripts folder** writes the script into `<minecraft>/baritone/alto/scripts/`. The first time, pick that
   folder. **Download .alto.json** always downloads the file.

Other things you can do:

- **Open** a `.alto.json` file (button, Ctrl+O, or drop it on the page), or load one of the **Examples**.
- **Variables**: "Make a variable" in the Variables category.
- **Your own blocks**: "Make a block" in My Blocks. Right-click its `define` block to rename it or change its inputs.
- **Item names**: run `#export-registry` in game, then **Load registry...** in the editor. Every item and block field
  then offers a searchable list of all ids, modded ones included.
- Undo and redo (Ctrl+Z, Ctrl+Y), save (Ctrl+S), dark mode, and autosave in the browser.

## In game

Alto reads scripts from `<minecraft>/baritone/alto/scripts/`. Before a new or changed script runs for the first time,
Alto shows what it may do and asks you to confirm (`#script run <name> confirm`).

## Privacy and safety

- The page loads nothing from the network. Everything is inside the file, and the page forbids network requests.
- It only stores the current script, the theme and a loaded registry in this browser's local storage.
- Folder access is given by you, for one folder and this session only. The editor only writes the file you save.
- Scripts cannot run code, read files or use the network. The blocks "send chat as me" and "run Baritone command"
  are marked with a warning sign: Alto refuses them unless its settings allow them. `forever` loops need Alto's
  safety stops to be on.

## For developers

```
npm ci
npm run build      writes dist/alto-script-editor.html (commit it; the tests check it is up to date)
npm test           runs the tests in Node, no browser needed
```

The tests compare the editor's checks with Alto's Java checks (same error codes, paths and messages), round-trip every
example between JSON and blocks, validate against `docs/alto/alto-script.schema.json`, check that the built page makes
no network requests, and, when a JDK 21 is found, run Alto's own Java validator on the same scripts.

| Path | What |
|---|---|
| `src/lang/` | JavaScript copy of Alto's script reader, validator and canonical writer |
| `src/blocks/` | Block definitions, generated from one table (`specs.js`) |
| `src/convert/` | Blocks to JSON and JSON to blocks |
| `src/app/` | The page: dialogs, files, autosave, themes |
| `build.mjs` | Bundles everything into the single HTML file |

The editor reports a few things Alto does not: blocks that are not attached to an event are listed as warnings and
are not saved. Files that blocks cannot show completely are opened as far as possible, with notes about what was left
out.

Blockly is licensed under the Apache License 2.0 (`THIRD_PARTY_LICENSES.txt`).
