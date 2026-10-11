# Alto script language, version 1

Alto scripts are small, Scratch-like programs that tell Alto (the Baritone fork) what to do: walk, mine, craft,
smelt, fetch from storage, build, farm, react to events. A script is a JSON file (`*.alto.json`) that the browser
editor writes and Alto runs. This document is the reference for every part of the language; the JSON Schema
[`alto-script.schema.json`](alto-script.schema.json) describes the same structure for editors, and the files in
[`examples/`](examples) are complete scripts that are tested on every build.

Contents

1. [A first script](#1-a-first-script)
2. [File format](#2-file-format)
3. [Values, types, names and ids](#3-values-types-names-and-ids)
4. [Events](#4-events)
5. [Control blocks](#5-control-blocks)
6. [Action blocks](#6-action-blocks)
7. [Expressions](#7-expressions)
8. [Variables and procedures](#8-variables-and-procedures)
9. [How a script runs](#9-how-a-script-runs)
10. [Safety rules](#10-safety-rules)
11. [Errors and warnings](#11-errors-and-warnings)
12. [Capability summary](#12-capability-summary)
13. [Saving a run across restarts](#13-saving-a-run-across-restarts)
14. [Canonical form, hash and versions](#14-canonical-form-hash-and-versions)
15. [Java API (for the Minecraft binding)](#15-java-api-for-the-minecraft-binding)

---

## 1. A first script

```json
{
  "format": "alto-script",
  "version": 1,
  "name": "Planks from logs",
  "events": [
    {
      "event": "on_start",
      "body": [
        {
          "block": "repeat_until",
          "condition": {"expr": "gte", "a": {"expr": "item_count", "item": "oak_planks"}, "b": 64},
          "body": [{"block": "craft", "item": "oak_planks", "count": 16}]
        },
        {"block": "message", "text": "64 planks ready."}
      ]
    }
  ]
}
```

* **Events** start lists of blocks (`on_start` runs once when the script starts).
* **Blocks** (statements) are objects `{"block": "<type>", ...fields}`.
* **Expressions** are plain numbers, strings and `true`/`false`, or objects `{"expr": "<kind>", ...fields}`.

---

## 2. File format

| Field         | Type                | Required | Meaning                                                      |
|---------------|---------------------|----------|--------------------------------------------------------------|
| `format`      | `"alto-script"`     | yes      | Marks the file as an Alto script.                            |
| `version`     | `1`                 | yes      | Language version. Other versions are refused.                |
| `name`        | string, 1-100 chars | yes      | Shown in Alto's script list.                                 |
| `description` | string, max 4000    | no       | Free text.                                                   |
| `variables`   | array               | no       | Global variables, see [8](#8-variables-and-procedures).      |
| `procedures`  | array               | no       | Custom blocks ("My Blocks"), see [8](#8-variables-and-procedures). |
| `events`      | array, at least one | yes      | Event handlers, see [4](#4-events).                          |

Rules that apply everywhere:

* The JSON must be strict: no comments, no trailing commas, no `NaN`, **no duplicate keys**, no `null` values
  (leave an optional field out instead).
* **Unknown fields are errors** (typos such as `"cout"` are reported with a "did you mean" hint).
* Size limits: 1,000,000 characters, JSON nesting depth 100, 20,000 blocks and expression objects.
* Field order does not matter; whitespace does not matter.

Every problem is reported with a **path** to where it is, for example `events[0].body[2].times` (the `times` field
of the third block in the first event handler), `procedures[1].params[0].type` or
`events[0].body[1].then[0].count.a`.

---

## 3. Values, types, names and ids

### Types

| Type      | Literals             | Notes                                                                 |
|-----------|----------------------|-----------------------------------------------------------------------|
| `number`  | `64`, `2.5`, `-3`    | 64-bit floating point; never NaN or infinite (that is an error).      |
| `string`  | `"oak_planks"`       | Text. Strings produced while running are limited to 10,000 characters. |
| `boolean` | `true`, `false`      | Conditions.                                                           |

The language is **statically typed**: every expression has one type that is known before the script runs.
Variables keep the type of their initial value; procedure parameters declare theirs. Using a value of the wrong
type (a string where a number is needed, a number as a condition) is an error found when the script is loaded.

Some number fields need **whole numbers** (counts, coordinates, `repeat` times, `random` bounds). A literal `1.5`
there is an error; a computed `5 / 2` is a runtime error (use `round`).

How values are shown (`message`, `join`): whole numbers without a fraction (`64`, not `64.0`), other numbers with up
to 12 significant digits (`0.333333333333`), booleans as `true`/`false`.

### Item and block ids

Ids are lower-case strings like `minecraft:oak_planks` or `create:shaft`. The namespace is optional:
`oak_planks` means `minecraft:oak_planks`. Allowed characters: `a-z 0-9 _ . -` and `/` in the path part, one
optional `namespace:` prefix. `Oak Planks` is an error. Alto always passes full ids (`minecraft:...`) to the game
and returns full ids from `block_at` and `dimension`; compare against full ids.

### Names

Variables, procedures and parameters: a letter or `_`, then letters, digits or `_`; at most 64 characters
(`count`, `max_passes`, `_tmp2`). Names are case-sensitive.

Homes, storage zones and train stations are free text of 1 to 64 characters.

### Schematic file names

`build` takes a plain file name from Alto's schematics folder: letters, digits, spaces and `_ . ( ) + -`; no
folders, no `..`, not starting with a dot or a space (`cabin.schem`, `My House (v2).litematic`).

---

## 4. Events

Every script has at least one event handler: `{"event": "<kind>", ...fields, "body": [blocks]}`. Event fields are
literals (fixed when the script is written). Each kind of event may appear more than once only with different
fields (two `on_start` handlers, or two `on_chat` handlers with the same trigger, are an error).

| Event               | Fields                                                   | Starts its handler when...                                 |
|---------------------|----------------------------------------------------------|------------------------------------------------------------|
| `on_start`          | -                                                        | the script starts (once).                                  |
| `on_chat`           | `trigger`: string, 1-64 chars, not starting with `/`     | **you** type exactly the trigger in chat.                  |
| `on_inventory_full` | -                                                        | the inventory becomes full.                                |
| `on_health_below`   | `health`: number 1-1024                                  | health drops below the number (20 = full, 1 heart = 2).    |
| `on_item_below`     | `item`: id, `count`: whole 1-100000, `where`: `inventory` (default) or `storage` | the count of the item drops below `count`. |
| `every`             | `seconds`: number 1-86400                                | every `seconds` seconds, first `seconds` after the start.  |

```json
{"event": "on_chat", "trigger": "!stopfarm", "body": [{"block": "stop"}]}
{"event": "every", "seconds": 300, "body": [{"block": "farm"}]}
{"event": "on_item_below", "item": "bread", "count": 4, "where": "inventory", "body": [{"block": "fetch", "item": "bread", "count": 16}]}
```

How events behave:

* **No re-entrancy, no queue.** A handler that is still running is not started a second time. A chat trigger typed
  while its handler runs is ignored; an `every` tick that comes while its handler still runs is skipped (the next one
  comes at the next multiple of `seconds`; missed ticks are not made up).
* **Chat triggers** match the typed text with surrounding spaces removed, ignoring upper/lower case (`!Go ` matches
  `!go`). Only text that you type yourself counts: messages from the server or other players never start a handler.
  Triggers cannot start with `/` (that would be a server command).
* **Condition events** (`on_inventory_full`, `on_health_below`, `on_item_below`) are checked about once per second
  and are **edge-triggered**: the handler starts when the condition becomes true and only starts again after the
  condition was false in between. If the condition is already true when the script starts, it starts at the first
  check. If the condition becomes true again while the handler is still running, the handler starts again right
  after it ends, provided the condition still holds then.
* Several handlers can be active at once (for example a long `on_start` loop and an `on_health_below` handler). They
  share the variables and take turns for actions, see [9](#9-how-a-script-runs).
* A script whose only handler is `on_start` ends when `on_start` ends. A script with any other handler keeps waiting
  for events until it runs `stop` or you stop it.

---

## 5. Control blocks

Bodies (`body`, `then`, `else`) are arrays of blocks and may be empty unless noted.

### `repeat`

| Field   | Type                                  |
|---------|---------------------------------------|
| `times` | whole number 0-100000 (may be computed) |
| `body`  | blocks                                |

Runs the body `times` times; 0 skips it. Literal `times` of 10,000 or more gets a warning.

```json
{"block": "repeat", "times": 3, "body": [{"block": "craft", "item": "stick", "count": 4}]}
```

### `repeat_until`

| Field       | Type    |
|-------------|---------|
| `condition` | boolean |
| `body`      | blocks  |

Checks the condition **before every pass** and stops as soon as it is true, so the body may run zero times.

### `forever`

| Field  | Type   |
|--------|--------|
| `body` | blocks |

Repeats the body until `break`, `stop`, or the run ends. Always gets a warning, and at runtime Alto **refuses to
start a `forever` loop unless safety stops are enabled** in its settings (the run fails with a clear message).
Blocks after a `forever` without any `break` can never run (warning).

### `if`

| Field       | Type                |
|-------------|---------------------|
| `condition` | boolean             |
| `then`      | blocks              |
| `else`      | blocks (optional)   |

```json
{"block": "if", "condition": {"expr": "lt", "a": {"expr": "health"}, "b": 10},
 "then": [{"block": "eat"}], "else": [{"block": "message", "text": "healthy"}]}
```

### `wait_until`

| Field             | Type                                |
|-------------------|-------------------------------------|
| `condition`       | boolean                             |
| `timeout_seconds` | number 0-86400 (optional)           |

Continues immediately when the condition is already true, otherwise checks it once per tick. With a timeout, the
**run fails** when the condition is still false after that many seconds ("wait_until timed out after 30 s"). If you
want "wait at most N seconds and then carry on", use a `repeat_until` with a counter and `wait`.

### `break`

No fields. Leaves the innermost `repeat`, `repeat_until` or `forever`. It must be inside a loop **of the same
body**: a `break` in a procedure cannot leave a loop of the caller (error `break_outside_loop`). Blocks after a
`break` in the same list never run (warning).

### `call`

| Field       | Type                                                      |
|-------------|-----------------------------------------------------------|
| `procedure` | procedure name                                            |
| `args`      | object: parameter name to value (may be left out if none) |

Every parameter must get a value of its type; unknown argument names are errors. See [8](#8-variables-and-procedures).

```json
{"block": "call", "procedure": "restock", "args": {"item": "glass_pane", "amount": 32}}
```

### `set_var`

| Field   | Type                                  |
|---------|---------------------------------------|
| `name`  | variable name                         |
| `value` | same type as the variable's initial value |

### `change_var`

| Field   | Type                  |
|---------|-----------------------|
| `name`  | name of a **number** variable |
| `delta` | number (may be negative) |

Adds `delta` to the variable.

---

## 6. Action blocks

Actions are performed by Alto in the world and usually take many ticks; the script waits until each action has
**succeeded**. If an action **fails** (for example "not enough oak logs"), the whole run fails with the block's path
and the reason. `wait`, `message` and `stop` are handled by the interpreter itself.

Field types: *count* = whole number 1-100000 (literal values of 10,000 or more get a warning); *coordinate* = whole
number between -30,000,000 and 30,000,000; *id* = item or block id ([3](#item-and-block-ids)). Except where noted as
*literal*, every field accepts an expression (for example a variable or a parameter).

### `goto`

| Field    | Type   |
|----------|--------|
| `target` | target object, one of the four below |

| Target                                                        | Meaning                                               |
|---------------------------------------------------------------|-------------------------------------------------------|
| `{"type": "coords", "x": c, "y": c, "z": c, "dimension": id?}` | A block position; `dimension` (literal id, e.g. `minecraft:the_nether`) defaults to the current one. |
| `{"type": "home", "name": "base"}`                            | A home set in Alto; without `name`, the default home. |
| `{"type": "storage_zone", "name": "main"}`                    | A named storage zone of Alto's storage index.         |
| `{"type": "portal"}`                                          | Walk to the nearest known portal (see `take_portal`). |

`name` and `dimension` are literals.

### `mine`

| Field           | Type  |
|-----------------|-------|
| `block_or_item` | id    |
| `count`         | count |

Mines until `count` more of the block (or of blocks that drop the item, e.g. `diamond` mines diamond ore) have been
collected.

### `build`

| Field       | Type                      |
|-------------|---------------------------|
| `schematic` | file name ([3](#schematic-file-names)) |
| `x`, `y`, `z` | coordinates of the schematic origin |

### `fill`

| Field                       | Type        |
|-----------------------------|-------------|
| `material`                  | block id    |
| `x1`, `y1`, `z1`, `x2`, `y2`, `z2` | coordinates of two opposite corners (inclusive) |

Fills the box with the block. The field is called `material` because `block` is the key that names the block type.
A literal box of more than 100,000 blocks gets a warning.

### `farm`

| Field   | Type                                   |
|---------|----------------------------------------|
| `range` | whole number 1-256 (optional; Alto's default otherwise) |
| `crops` | array of ids (optional, at least one entry; every crop otherwise) |

One pass of harvesting and replanting nearby crops.

### `craft`, `smelt`, `fetch`

| Field   | Type  |
|---------|-------|
| `item`  | id    |
| `count` | count |

* `craft`: craft `count` **more** of the item (Alto's planner picks recipes and stations).
* `smelt`: smelt until `count` more of the **result** (e.g. `iron_ingot`) have been made.
* `fetch`: take `count` of the item out of storage Alto knows.

### `deposit`

| Field    | Type                                       |
|----------|--------------------------------------------|
| `except` | array of ids (optional): keep these        |
| `only`   | array of ids (optional): deposit only these |

Puts inventory items into storage. Equipped armor, the offhand and tools in use always stay. Listed arrays must not
be empty (leave the field out instead).

### `eat`

No fields. Eats from the inventory until no longer hungry (does nothing when not hungry).

### `wait`

| Field     | Type            |
|-----------|-----------------|
| `seconds` | number 0-86400  |

Waits; `0` waits until the next tick. Timers use the game clock and are frozen while the run is paused.

### `message`

| Field  | Type                        |
|--------|-----------------------------|
| `text` | any value (shown as text)   |

Shows a message **to you only** (never sent to the server). Texts longer than 1000 characters are cut.

### `command`

| Field     | Type                                             |
|-----------|--------------------------------------------------|
| `command` | *literal* string, 1-256 chars, a Baritone command |

Runs a Baritone command such as `mine diamond_ore` (a leading `#` is allowed and ignored). The command is a literal
so the confirmation screen can show it. It only runs when it is on the **allowed commands list** in Alto's settings;
otherwise the run fails ("command refused"). Text starting with `/` is a server command and an error (that would be
`send_chat`). Always gets a warning.

### `send_chat`

| Field  | Type               |
|--------|--------------------|
| `text` | string, max 256    |

Sends a **real chat message as you** (text starting with `/` is run as a server command by the server). Alto
**refuses it unless the setting that allows scripts to chat is on** (the run fails with "send_chat refused"). Two
`send_chat` actions are at least one second apart. Always gets a warning.

### `use_item_on_block`

| Field         | Type                                                   |
|---------------|--------------------------------------------------------|
| `item`        | id                                                     |
| `x`, `y`, `z` | coordinates                                            |
| `face`        | *literal* `up`, `down`, `north`, `south`, `east`, `west` |

Right-clicks that face of the block while holding the item.

### `set_filter`

| Field         | Type                          |
|---------------|-------------------------------|
| `x`, `y`, `z` | coordinates                   |
| `face`        | *literal* face (optional)     |
| `item`        | id                            |

Sets the filter of the block at the position (e.g. a Create deployer or funnel) to the item. Without `face` Alto uses
the side that has the filter slot.

### `place_station`, `pickup_station`

| Field  | Type                                                              |
|--------|-------------------------------------------------------------------|
| `kind` | *literal* `crafting_table`, `furnace`, `blast_furnace` or `smoker` |

Place a station from the inventory next to you; pick up a station **Alto placed** (never one you built).

### `board_vehicle`

| Field  | Type                                                     |
|--------|----------------------------------------------------------|
| `kind` | *literal* `boat`, `minecart`, `train_seat` or `nearest`  |

### `ride_train`

| Field          | Type                  |
|----------------|-----------------------|
| `from_station` | string, 1-64 chars    |
| `to_station`   | string, 1-64 chars    |

Rides a Create train from one named station to another.

### `leave_vehicle`, `take_portal`

No fields. `take_portal` goes through the portal next to you (or the nearest known one) and waits for the new
dimension.

### `stop`

No fields. Ends the script (status *finished*) and cancels any action that is still running, also in other
handlers. Blocks after `stop` in the same list never run (warning).

---

## 7. Expressions

A literal (`64`, `"oak_log"`, `true`) or an object `{"expr": "<kind>", ...}`. Operands are called `a` and `b`.

### Values and state

| Expression                                          | Type    | Value                                                          |
|-----------------------------------------------------|---------|----------------------------------------------------------------|
| `{"expr": "var", "name": "count"}`                  | of the variable | a variable                                             |
| `{"expr": "param", "name": "item"}`                 | of the parameter | a parameter; only inside its procedure                |
| `{"expr": "item_count", "item": id, "where": "inventory"}` | number | how many of the item; `where` is `inventory` (default) or `storage` (containers Alto knows) |
| `{"expr": "health"}`                                | number  | health points, 20 = full                                       |
| `{"expr": "hunger"}`                                | number  | food level 0-20                                                |
| `{"expr": "position", "axis": "y"}`                 | number  | your block position on `x`, `y` or `z`                         |
| `{"expr": "time_of_day"}`                           | number  | 0-23999 (0 sunrise, 6000 noon, 18000 midnight)                 |
| `{"expr": "dimension"}`                             | string  | e.g. `minecraft:overworld`                                     |
| `{"expr": "block_at", "x": c, "y": c, "z": c}`      | string  | block id there; `minecraft:air` for air or unloaded chunks     |
| `{"expr": "inventory_full"}`                        | boolean | no free inventory slot                                         |
| `{"expr": "random", "min": 1, "max": 6}`            | number  | random whole number from min to max inclusive (swapped if min > max; bounds within +-1,000,000,000) |

### Math (numbers to number)

`add`, `sub`, `mul`, `div`, `mod`, `min`, `max` take `a` and `b`; `abs` and `round` take `a`.

* `div` and `mod` by zero are runtime errors.
* `mod` is floored: the result has the sign of `b` (`mod(-1, 5) = 4`).
* `round` rounds half up (`round(2.5) = 3`, `round(-2.5) = -2`).
* A result that is too large to represent is a runtime error.

```json
{"expr": "sub", "a": 64, "b": {"expr": "item_count", "item": "raw_iron"}}
```

### Comparisons (to boolean)

`lt`, `lte`, `gt`, `gte` compare two numbers. `eq` and `neq` compare two values **of the same type** (comparing a
number with a string is an error); strings are compared exactly (case-sensitive).

### Logic (booleans to boolean)

`and`, `or` take `a` and `b` and short-circuit (`b` is not evaluated when `a` decides). `not` takes `a`.

### Text

`{"expr": "join", "parts": [...]}` joins the shown form of every part into one string:

```json
{"expr": "join", "parts": ["Pass ", {"expr": "var", "name": "passes"}, " done"]}
```

---

## 8. Variables and procedures

### Variables

```json
"variables": [{"name": "passes", "initial": 0}, {"name": "label", "initial": "farm"}, {"name": "enabled", "initial": true}]
```

Variables are global: every handler and procedure sees the same values. The initial value (a literal) fixes the
type for the whole run. Unused variables get a warning.

### Procedures ("My Blocks")

```json
"procedures": [{
  "name": "restock",
  "params": [{"name": "item", "type": "item"}, {"name": "amount", "type": "number"}],
  "body": [ ... {"expr": "param", "name": "item"} ... ]
}]
```

Parameter types: `number`, `string`, `boolean`, `item`, `block`. `item` and `block` parameters hold ids: values are
checked and normalized (`glass` becomes `minecraft:glass`) when the procedure is called.

Rules:

* A procedure runs inside the handler that called it; `stop` in a procedure ends the whole script.
* **No recursion**: a procedure may not call itself, directly or through other procedures (error with the cycle,
  e.g. `a -> b -> a`). Use a loop instead.
* Calls nest at most 32 deep at runtime.
* Unused procedures get a warning.

---

## 9. How a script runs

Alto runs scripts inside the game loop, one step at a time, without threads, so a script can never freeze the
game. Everything below is deterministic: the same script in the same world does the same thing.

### Ticks and the step budget

Every game tick the interpreter:

1. checks whether the running action has finished (an action always takes at least one tick);
2. starts due event handlers (`every` timers, condition checks about once per second);
3. runs every active handler, oldest first, until each one **waits**: for an action, a `wait` timer, a false
   `wait_until`, its turn for an action, or the chat cooldown.

Executing a block costs one step, evaluating an expression object or literal costs one step, starting another pass
of a loop costs one step. A tick runs about **200 steps** at most (all handlers together; the budget is checked
between blocks); the rest continues next tick.

### The loop guard

A loop that never waits would spin forever without giving the game time. Two guards prevent that:

* **When loading**: every `repeat`, `repeat_until` and `forever` body must contain, somewhere inside (also in
  `if` branches, nested loops and called procedures), at least one block that gives the game time: `wait`,
  `wait_until`, or any action except `message` and `stop`. Otherwise: error *loop without a wait or task step*.
* **While running**: a handler that uses up the whole step budget in two ticks in a row while looping, without
  waiting once in between, fails the run ("runtime loop guard"). This catches loops whose waiting block is in a branch
  that is never taken, or a `wait_until` whose condition is always true.

### One body, taking turns

Only one action runs at a time (there is one player). When a handler wants to start an action while another
handler's action is running, it waits for its turn; turns are given first come, first served. Actions are never
interrupted by other handlers; Alto's own safety stops (low health, danger) still work inside long actions.

### Ending

| Status     | When                                                                                     |
|------------|------------------------------------------------------------------------------------------|
| running    | executing, waiting, or idle between events                                               |
| paused     | you paused it                                                                            |
| finished   | all handlers ended and none can start again, or a `stop` block ran                       |
| failed     | an action failed, or a runtime error (division by zero, a value out of range, a refused `command`/`send_chat`/`forever`, a `wait_until` timeout, the runtime loop guard). The error names the block path. |
| stopped    | you stopped it, or a run limit was reached (optional maximum run time or number of actions) |

Any failure ends the **whole** script; there is no error handling inside scripts.

### Pause, resume, stop

* **Pause** cancels the running action (the bot stops), freezes `wait` timers, `wait_until` timeouts and `every`
  timers, and ignores events. **Resume** continues; the cancelled action block runs again from its start (with its
  values evaluated again).
* **Stop** cancels the running action and ends the run.

---

## 10. Safety rules

* `forever` only starts when Alto's safety stops are enabled.
* `send_chat` is refused unless scripts are explicitly allowed to chat; at most one message per second.
* `command` only runs Baritone commands on the allowed commands list; the commands are literals, so the confirmation
  screen lists them all.
* `message` is local; chat triggers only react to text you type yourself.
* `build` only reads plain file names from Alto's schematics folder.
* Before a script runs for the first time (or after it changed), Alto shows what it may do (the capability summary,
  [12](#12-capability-summary)) and its warnings, and asks you to confirm. The script's hash
  ([14](#14-canonical-form-hash-and-versions)) identifies the confirmed version.
* A run saved before a restart is never resumed automatically; Alto asks first.
* Scripts cannot read or write files, open network connections or run anything but the blocks above.

---

## 11. Errors and warnings

Errors stop a script from running; warnings are shown on the confirmation screen. Each diagnostic has a stable code
(used by the editor), a path and a message.

| Code                      | Kind    | Meaning                                                                      |
|---------------------------|---------|------------------------------------------------------------------------------|
| `json_syntax`             | error   | Not valid strict JSON (also duplicate keys, nesting deeper than 100).        |
| `too_large`               | error   | More than 1,000,000 characters or 20,000 blocks.                             |
| `unsupported_format`      | error   | `format` is not `alto-script`.                                               |
| `unsupported_version`     | error   | `version` is not 1.                                                          |
| `missing_field`           | error   | A required field is missing.                                                 |
| `unknown_field`           | error   | A field that the block does not have (with a "did you mean" hint).           |
| `wrong_json_type`         | error   | E.g. an array where a value is expected, `null`, a computed `command`.       |
| `unknown_block`           | error   | Unknown `block` type.                                                        |
| `unknown_expr`            | error   | Unknown `expr` kind.                                                         |
| `unknown_event`           | error   | Unknown `event` kind.                                                        |
| `invalid_choice`          | error   | A value not in the fixed list (`kind`, `face`, `where`, `axis`, target `type`, parameter `type`). |
| `invalid_name`            | error   | A variable, procedure or parameter name that is not an identifier.           |
| `duplicate_name`          | error   | Two variables, procedures or parameters of one procedure with the same name. |
| `duplicate_event`         | error   | Two handlers for the same event (e.g. two `on_start`, same chat trigger).    |
| `no_events`               | error   | The script has no event handler.                                             |
| `unknown_variable`        | error   | Use of a variable that is not declared.                                      |
| `unknown_procedure`       | error   | `call` of a procedure that does not exist.                                   |
| `unknown_param`           | error   | `param` that the procedure does not declare.                                 |
| `param_outside_procedure` | error   | `param` used in an event handler.                                            |
| `missing_argument`        | error   | `call` without a value for a parameter.                                      |
| `unknown_argument`        | error   | `call` with an argument the procedure does not have.                         |
| `type_mismatch`           | error   | A value of the wrong type.                                                   |
| `not_whole_number`        | error   | A fraction where a whole number is needed.                                   |
| `out_of_range`            | error   | A number outside its range, a text that is too long, an empty item list.     |
| `invalid_id`              | error   | An item/block id with invalid characters.                                    |
| `invalid_file_name`       | error   | A schematic name with folders, `..` or other characters.                     |
| `invalid_text`            | error   | An empty name/command/trigger, a `command` or trigger starting with `/`.     |
| `recursion`               | error   | A procedure that would call itself.                                          |
| `break_outside_loop`      | error   | `break` not inside a loop of the same body.                                  |
| `loop_without_wait`       | error   | A loop without a wait or task step ([9](#the-loop-guard)).                   |
| `forever_loop`            | warning | `forever` runs until stopped and needs safety stops.                         |
| `sends_chat`              | warning | `send_chat` sends real chat as you and needs the chat setting.               |
| `runs_command`            | warning | `command` needs the command on the allowed list.                             |
| `large_count`             | warning | A literal count or `repeat` of 10,000 or more, or a `fill` over 100,000 blocks. |
| `unreachable_code`        | warning | Blocks after `stop`, `break` or a `forever` without `break`.                 |
| `unused_procedure`        | warning | A procedure that is never called.                                            |
| `unused_variable`         | warning | A variable that is never used.                                               |

---

## 12. Capability summary

Alto lists what a script may do before its first run. Every block in the file counts, also in branches that might
not run.

| Capability               | Shown as                                        | Caused by                                                            |
|--------------------------|-------------------------------------------------|----------------------------------------------------------------------|
| `MOVES`                  | walks around                                    | goto, mine, build, fill, farm, craft, smelt, fetch, deposit, use_item_on_block, set_filter, board_vehicle, ride_train, take_portal |
| `BREAKS_BLOCKS`          | breaks blocks                                   | mine, build, fill, farm, pickup_station                              |
| `PLACES_BLOCKS`          | places blocks                                   | build, fill, farm, place_station                                     |
| `USES_ITEMS`             | uses items (right-clicks blocks, eats)          | use_item_on_block, eat, set_filter                                   |
| `CRAFTS`                 | crafts items                                    | craft                                                                |
| `SMELTS`                 | smelts items in furnaces                        | smelt                                                                |
| `MOVES_ITEMS_TO_STORAGE` | puts items from your inventory into storage     | deposit                                                              |
| `TAKES_FROM_STORAGE`     | takes items out of storage                      | fetch                                                                |
| `TRAVELS_PORTALS`        | walks through portals                           | take_portal                                                          |
| `RIDES_VEHICLES`         | rides boats, minecarts or trains                | board_vehicle, ride_train, leave_vehicle                             |
| `RUNS_COMMANDS`          | runs Baritone commands: *the list*              | command                                                              |
| `SENDS_CHAT`             | sends chat messages or server commands as you: *the texts* | send_chat (computed texts are shown as "(text computed while the script runs)") |
| `SETS_FILTERS`           | changes filters on blocks                       | set_filter                                                           |
| `RUNS_UNTIL_STOPPED`     | keeps running until you stop it                 | forever, and any handler other than on_start                         |
| `LISTENS_TO_CHAT`        | reacts to chat triggers that you type: *the triggers* | on_chat                                                        |

---

## 13. Saving a run across restarts

When the game closes while a script runs, Alto can save a small snapshot:

```json
{
  "format": "alto-script-snapshot",
  "version": 1,
  "scriptHash": "<sha-256 of the script>",
  "savedAtMillis": 1234567,
  "variables": {"passes": 3, "label": "farm"},
  "activeHandlers": ["events[1]"]
}
```

After a restart Alto **asks** whether to continue. Continuing restores exactly this:

* the values of the variables (only variables that still exist with the same type);
* `on_start` runs again **from its beginning**, with the restored values;
* `on_chat` handlers that were active are started again from their beginning;
* `every` timers start counting from the moment of continuing; condition events are checked again.

Nothing else is restored: not the position inside a handler, not the running action, not timers or procedure calls.
A snapshot only fits the exact script it was taken from: if the script changed (different hash), continuing is
refused. Write scripts so that starting them again is safe, for example check `item_count` before crafting more.

---

## 14. Canonical form, hash and versions

Alto writes scripts in a **canonical form**: fixed key order (as in this document), whole numbers without fraction
(`64`, not `64.0`), defaults written out (`"where": "inventory"`), an empty `else` left out, call arguments sorted
by name, all top-level fields present. The **hash** is the SHA-256 of the compact canonical form (64 hex digits).
Formatting, key order or `64` versus `64.0` do not change it; any change to names, values, texts or blocks does. Alto
uses the hash to notice new or changed scripts and asks for confirmation again.

Exact rules of the compact canonical form, for tools that want to compute the same hash:

* no whitespace between tokens; UTF-8;
* keys in the order of the tables in this document: top level `format, version, name, description, variables,
  procedures, events`; variables `name, initial`; procedures `name, params, body`; parameters `name, type`; handlers
  `event`, then the event fields (`trigger` / `health` / `item, count, where` / `seconds`), then `body`; blocks
  `block`, then the fields in the order of their table; expressions `expr`, then the fields in table order; targets
  `type, x, y, z, dimension` or `type, name`; `call` arguments sorted by name;
* all top-level fields are written (`description` as `""`, empty arrays as `[]`); other optional fields only when
  present (`else` only when not empty); defaults that have a value (`where`) are always written; `call` always has
  `args` (possibly `{}`);
* numbers: whole numbers below 2^53 as integers (`64`, `-3`); other numbers as Java's `Double.toString` (the shortest
  form that reads back as the same number: plain between 0.001 and 10,000,000 such as `0.25`, otherwise scientific
  such as `1.0E-5`);
* strings: `"` and `\` escaped with a backslash; backspace, form feed, newline, carriage return and tab as `\b`,
  `\f`, `\n`, `\r`, `\t`; other characters below U+0020 and U+2028/U+2029 as `\u` with four lower-case hex digits;
  everything else as is.

`version` is the language version. Version 1 is described here; Alto refuses scripts with another version instead of
guessing. Later versions will only add things in a way that version 1 scripts keep their meaning.

---

## 15. Java API (for the Minecraft binding)

Package `baritone.alto.script` and below; pure Java (no Minecraft classes), no file or network access.

| Class                              | Use                                                                          |
|------------------------------------|------------------------------------------------------------------------------|
| `ScriptLoader.load(String json)`   | Parse + validate + hash + capabilities: a `ScriptReport`.                   |
| `codec.ScriptCodec`                | `parse(String)`, `parse(Reader)`, `parseOrThrow`, `toCanonicalJson`, `toPrettyJson`, `hash`. |
| `check.ScriptValidator`            | `validate(Script)`: list of `lang.Diagnostic`.                              |
| `check.CapabilitySummary`          | `of(Script)`, `sentences()`, `commands()`, `chatTexts()`, `chatTriggers()`.  |
| `runtime.ScriptRun`                | `new ScriptRun(script, host, options)`, `tick()`, `fire(EventType, payload)`, `pause()`, `resume()`, `stop()`, `snapshot()`, `restore(...)`, state getters. |
| `runtime.ScriptHost`               | Implemented by the binding: `start(ActionCall)`, `query(QueryCall)`, `safetyStopsEnabled()`, `chatAllowed()`, `commandAllowed(String)`, `showMessage(String)`, `nowMillis()`. |
| `runtime.ActionHandle`             | Returned by `start`: `poll()`, `failureReason()`, `cancel()`.               |
| `runtime.ActionCall` / `ActionRequest` | The action to perform, with evaluated, checked, normalized arguments (one record per action). |
| `runtime.QueryCall`                | World questions for expressions and condition events.                        |
| `runtime.RunOptions`               | Step budget (200), loop guard ticks (2), call depth (32), run limits, condition check interval (1 s), chat interval (1 s), random seed. |
