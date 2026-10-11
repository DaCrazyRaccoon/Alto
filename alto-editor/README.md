# Alto Script Editor

A Scratch-like block editor for Alto scripts (`*.alto.json`, see
[`docs/alto/SCRIPT_LANGUAGE.md`](../docs/alto/SCRIPT_LANGUAGE.md)). It is **one HTML file** that works offline,
straight from disk: no server, no internet, no installation.

## Open the editor

Double-click **`dist/alto-script-editor.html`** (or drag it into a browser window). Chrome and Edge give the best
experience because they can write straight into Alto's scripts folder; Firefox and Safari work too and download
the script instead.

What you can do:

* **Build scripts with blocks.** The palette has Events, Movement, Mining & Building, Crafting & Items, Storage,
  Create, Vehicles & Portals, Control, Values, Variables and My Blocks. Type in **Search blocks** to filter the
  palette by name (`craft`, `wait`, `chat`, ...). Blocks only snap where their type fits (numbers, texts,
  true/false).
* **Variables**: *Make a variable* (name, type, initial value). Get/set/change blocks appear in the Variables
  category; initial values, renaming and deleting are in the **Script** tab.
* **Custom blocks** ("My Blocks"): *Make a block* creates a `define` block with parameters; the call block and
  the parameter blocks appear in My Blocks. Right-click a `define` block to rename it or change its parameters
  (all uses are updated; Undo reverts it).
* **Live checking**: the **Problems** tab runs the same checks as Alto (same error codes and messages), shows
  where each problem is (`every 300 seconds › if › stop` plus the JSON path) and marks the block; click a problem
  to jump to its block. A loop without a wait or an action is an error, just like in game. Below the problems you
  see what the script may do, worded like Alto's confirmation screen, and the script's version hash.
* **Save to scripts folder**: the first time, pick `<minecraft>/baritone/alto/scripts/`; the editor remembers
  the folder until you close the page and asks before replacing an existing file. Without folder access (Firefox,
  Safari) the script is downloaded; move it into that folder. **Download .alto.json** always downloads.
* **Open** a `.alto.json` file (button, Ctrl+O, or drop the file onto the page), or load one of the **Examples**.
* **Item and block names**: in game, run `#export-registry`; it writes `alto-registry.json`. Use
  *Load registry…* (or drop the file onto the page) and every item/block field gets a searchable picker with all
  ids and names, modded ones included. Without it a short list of common vanilla ids is used and any id can be
  typed (`oak_planks` means `minecraft:oak_planks`).
* **JSON** tab: the exact file content (Alto's canonical form) with a Copy button.
* Undo/Redo (buttons, Ctrl+Z / Ctrl+Y), autosave in the browser, dark mode (follows the system setting until you
  pick one), Ctrl+S saves.

## Where scripts go in game

Alto reads scripts from **`<minecraft>/baritone/alto/scripts/`**. Before a new or changed script runs for the
first time, Alto shows what it may do and its warnings and asks you to confirm.

## Privacy and safety

* The page loads nothing from the network: all code and styles are inside the file, and its
  Content-Security-Policy (`default-src 'none'; connect-src 'none'; ...`) makes the browser refuse any request.
* It stores only in this browser's localStorage: `alto-editor.autosave` (the current workspace),
  `alto-editor.theme` and `alto-editor.registry` (the loaded registry, when it fits). If storage is blocked the
  editor still works, without autosave.
* Folder access (File System Access API) is granted by you for one folder, only for this page and this session;
  the editor writes only the script file you save.
* Scripts themselves cannot run code, read files or use the network. `send chat as me` and `run Baritone
  command` are marked with ⚠: Alto refuses them unless its settings allow chat / the command; `forever` loops need
  Alto's safety stops.

## Build

```
npm ci
npm run build
```

`npm run build` writes `dist/alto-script-editor.html` (Blockly and the editor bundled by esbuild into one inline
script, the CSS and the five example scripts inlined). Commit the rebuilt file: the tests check that it is up to
date. (npm may mention esbuild's install script; the build works without running it because the platform
binary package is installed anyway.)

## Test

```
npm test
```

runs, in Node (no browser needed):

* `test/validator.test.mjs` — the JavaScript validator reports the same codes at the same paths as the Java
  validator for the bad scripts of `ValidatorTest.java` / `CodecTest.java` (one per diagnostic code and many
  more), plus number formatting and SHA-256 checks.
* `test/roundtrip.test.mjs` — the five examples go JSON → blocks → (Blockly save/load) → JSON and come back with
  identical canonical JSON and hash; every structurally valid test script round-trips; palette, search, path
  mapping. The outputs are written to `test-output/*.alto.json` (git-ignored) for the Java side.
* `test/schema.test.mjs` — every example and every generated script (round trips and a script made of every
  palette block) is valid against `docs/alto/alto-script.schema.json` (ajv, draft 2020-12).
* `test/build.test.mjs` — builds the page and checks: no http(s) script/link/style/media references, the CSP,
  the size, the license text, and that `dist/` is current.
* `test/offline.test.mjs` — opens the built page from a `file://` URL in jsdom with a loader that records every
  request (there must be none), opens every example through the page's own code, works without storage,
  restores the autosave.
* `test/java-crosscheck.test.mjs` — if a JDK 21+ and a Gson jar are found (`~/.jdks`, `~/.gradle`, or
  `ALTO_JDK` / `ALTO_GSON_JAR`), compiles Alto's own `baritone.alto.script` package into `test-output/` and
  compares the complete reports (diagnostics with messages, canonical and pretty JSON, hash, capability sentences)
  of JavaScript and Java for all test scripts and the editor's outputs. Skipped otherwise. `npm run crosscheck`
  runs the same comparison including every file in `test-output/`.

## How it is built

| Path | What |
|------|------|
| `src/lang/` | JavaScript port of `baritone.alto.script` (strict JSON reader, AST reader, validator, canonical writer, capability summary, SHA-256); no DOM, no Blockly |
| `src/blocks/specs.js` | One table describing every block and its JSON mapping; block definitions, toolbox, search and both converters are generated from it |
| `src/blocks/dynamic.js` | Blocks with a changing shape: farm, deposit, join, custom blocks (define/call/parameter) |
| `src/blocks/fields.js` | Item/block id picker and the text field with a hint when empty |
| `src/convert/` | Workspace → script JSON (with a map from JSON paths to blocks) and script → workspace |
| `src/app/` | Page, styles, dialogs, files, autosave, registry, themes |
| `build.mjs` | esbuild bundle → `dist/alto-script-editor.html` |

## Where the editor differs from Alto's validator (on purpose)

* **JSON syntax messages** are worded differently (Gson writes its own); the code (`json_syntax`) and the path
  are the same. Everything else — codes, paths, messages and their order — is identical (checked against the
  Java code by the cross-check test).
* **Editor-only notes**: blocks that are not attached to an event or a `define` block are reported as
  `detached_block` warnings and are not saved (like loose blocks in Scratch).
* **Opening files**: only files that Alto could read are opened as blocks; for others the structural errors are
  listed. When an opened file has a semantic problem that blocks cannot show, the editor keeps as much as it can
  and lists *import notes*: a value whose type does not fit its slot is placed next to the script (the slot stays
  empty and is reported), an undeclared variable is created, a second variable with the same name is dropped,
  and an empty `except`/`only`/`crops` list (an error in Alto) is left out.
* **Empty slots** make the exported JSON miss that field, which Alto (and the editor) reports as `missing_field`.
  Like Alto, the deeper checks (types, loops, ...) run once the script is structurally complete.
* **Typing ids**: the picker turns typed text into id form (lower case, spaces to `_`) and marks invalid ids in
  red; ids in opened files are kept exactly as written.
* Many errors cannot happen in the editor at all (unknown blocks or fields, wrong JSON types, invalid choices,
  most type mismatches), because blocks only offer valid options; they can still appear for opened files.
