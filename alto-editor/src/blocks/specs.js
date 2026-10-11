// Declarative description of every Blockly block of the editor and how it maps to script JSON. Block definitions,
// the toolbox, search and both converters are generated from this table, so a block cannot exist in one place
// and be missing in another. Blocks with a dynamic shape (farm, deposit, join, My Blocks) add their own code in
// dynamic.js but are described here too.

/** Category keys in toolbox order, with their labels. */
export const CATEGORIES = [
  ['events', 'Events'],
  ['movement', 'Movement'],
  ['mining', 'Mining & Building'],
  ['crafting', 'Crafting & Items'],
  ['storage', 'Storage'],
  ['create', 'Create'],
  ['vehicles', 'Vehicles & Portals'],
  ['control', 'Control'],
  ['values', 'Values'],
  ['variables', 'Variables'],
  ['procedures', 'My Blocks'],
];

/** Block colours per style; text is white on all of them. */
export const STYLE_COLOURS = {
  events: '#c88a00',
  movement: '#3b7be0',
  mining: '#9c6236',
  crafting: '#8455d9',
  storage: '#2e8a55',
  create: '#a8742a',
  vehicles: '#24879f',
  control: '#c76a00',
  values: '#2f9441',
  sensing: '#2f7fb8',
  variables: '#d4561c',
  procedures: '#cc3d63',
  literal: '#5c6470',
};

// ------------------------------------------------------------------------------------------ argument helpers

const NUM = 'Number';
const STR = 'String';
const BOOL = 'Boolean';

/** A value input holding a number expression, default shadow alto_number. */
const num = (json, def, extra = {}) => ({ kind: 'value', json, check: NUM, shadow: 'alto_number', default: def, ...extra });
/** A value input holding a string expression, default shadow alto_text. */
const str = (json, def, extra = {}) => ({ kind: 'value', json, check: STR, shadow: 'alto_text', default: def, ...extra });
/** A value input holding an item/block id expression. */
const idv = (json, registry, def, extra = {}) => ({
  kind: 'value', json, check: STR, shadow: registry === 'block' ? 'alto_block' : registry === 'item' ? 'alto_item' : 'alto_id', default: def, ...extra,
});
/** A value input for any type, default shadow alto_text. */
const anyv = (json, def, extra = {}) => ({ kind: 'value', json, check: null, shadow: 'alto_text', default: def, ...extra });
/** A boolean value input, no shadow (an empty hexagon until a condition is put in). */
const cond = (json) => ({ kind: 'value', json, check: BOOL, shadow: null });
/** A dropdown field holding a literal choice; omitValue means "leave the JSON field out". */
const choice = (json, options, extra = {}) => ({ kind: 'dropdown', json, options, ...extra });
/** A literal text field. */
const text = (json, def, extra = {}) => ({ kind: 'text', json, default: def, ...extra });
/** A literal number field (event fields). */
const litnum = (json, def) => ({ kind: 'number', json, default: def });
/** A literal id field (event item, goto dimension). */
const litid = (json, registry, def, extra = {}) => ({ kind: 'id', json, registry, default: def, ...extra });
/** A statement input. */
const stmts = (json) => ({ kind: 'statements', json });

const FACE_OPTIONS = [['top', 'up'], ['bottom', 'down'], ['north', 'north'], ['south', 'south'], ['east', 'east'], ['west', 'west']];
const STATION_OPTIONS = [['crafting table', 'crafting_table'], ['furnace', 'furnace'], ['blast furnace', 'blast_furnace'], ['smoker', 'smoker']];
const VEHICLE_OPTIONS = [['boat', 'boat'], ['minecart', 'minecart'], ['Create train seat', 'train_seat'], ['nearest vehicle', 'nearest']];
const WHERE_OPTIONS = [['inventory', 'inventory'], ['storage', 'storage']];

// ------------------------------------------------------------------------------------------ the table

/**
 * shape: hat | statement | c (statement with statement inputs) | reporter
 * json: {event} | {block} | {block:'goto', target} | {expr} | {literal: type}
 * args: in canonical JSON order (the order the converter writes); message uses {name} placeholders.
 */
export const SPECS = [
  // ---------------------------------------------------------------- events
  {
    type: 'alto_on_start', category: 'events', shape: 'hat', json: { event: 'on_start' },
    message: 'when the script starts', args: {},
    tooltip: 'Runs once when the script starts. A script with only this handler ends when the blocks below it are done.',
  },
  {
    type: 'alto_on_chat', category: 'events', shape: 'hat', json: { event: 'on_chat' },
    message: 'when I type {trigger} in chat', args: { trigger: text('trigger', '!go') },
    tooltip: 'Runs when you type exactly this text in chat (case and surrounding spaces are ignored). Only text you type yourself counts. Cannot start with /.',
  },
  {
    type: 'alto_on_inventory_full', category: 'events', shape: 'hat', json: { event: 'on_inventory_full' },
    message: 'when my inventory is full', args: {},
    tooltip: 'Runs when the inventory becomes full (checked about once per second).',
  },
  {
    type: 'alto_on_health_below', category: 'events', shape: 'hat', json: { event: 'on_health_below' },
    message: 'when my health drops below {health}', args: { health: litnum('health', 8) },
    tooltip: 'Runs when health drops below the number (20 = full health, 2 = one heart). Allowed: 1 to 1024.',
  },
  {
    type: 'alto_on_item_below', category: 'events', shape: 'hat', json: { event: 'on_item_below' },
    message: 'when {item} in my {where} drops below {count}',
    args: { item: litid('item', 'item', 'minecraft:bread'), count: litnum('count', 4), where: choice('where', WHERE_OPTIONS) },
    tooltip: 'Runs when the count of the item in the inventory (or in storage Alto knows) drops below the number (a whole number 1 to 100000).',
  },
  {
    type: 'alto_every', category: 'events', shape: 'hat', json: { event: 'every' },
    message: 'every {seconds} seconds', args: { seconds: litnum('seconds', 300) },
    tooltip: 'Runs every N seconds, the first time N seconds after the start (1 to 86400). A tick that comes while the handler still runs is skipped.',
  },

  // ---------------------------------------------------------------- movement
  {
    type: 'alto_goto_coords', category: 'movement', shape: 'statement', json: { block: 'goto', target: 'coords' },
    message: 'go to x {x} y {y} z {z} in dimension {dimension}',
    args: { x: num('x', 0), y: num('y', 64), z: num('z', 0), dimension: litid('dimension', 'dimension', '', { emptyText: 'current', allowEmpty: true }) },
    tooltip: 'Walks to a block position (whole numbers within the world border). Leave the dimension empty for the current one, or pick e.g. minecraft:the_nether.',
  },
  {
    type: 'alto_goto_home', category: 'movement', shape: 'statement', json: { block: 'goto', target: 'home' },
    message: 'go to home {name}', args: { name: text('name', '', { emptyText: 'default', omitIfEmpty: true }) },
    tooltip: 'Walks to a home set in Alto. Leave the name empty for the default home.',
  },
  {
    type: 'alto_goto_storage_zone', category: 'movement', shape: 'statement', json: { block: 'goto', target: 'storage_zone' },
    message: 'go to storage zone {name}', args: { name: text('name', 'main') },
    tooltip: "Walks to a named storage zone of Alto's storage index.",
  },
  {
    type: 'alto_goto_portal', category: 'movement', shape: 'statement', json: { block: 'goto', target: 'portal' },
    message: 'go to the nearest known portal', args: {},
    tooltip: 'Walks next to the nearest known portal (it does not go through; use "go through the portal" for that).',
  },

  // ---------------------------------------------------------------- mining & building
  {
    type: 'alto_mine', category: 'mining', shape: 'statement', json: { block: 'mine' },
    message: 'mine {count} more {block_or_item}', args: { block_or_item: idv('block_or_item', 'any', 'minecraft:coal_ore'), count: num('count', 16) },
    tooltip: 'Mines until this many more of the block (or of blocks that drop the item, e.g. diamond mines diamond ore) have been collected.',
  },
  {
    type: 'alto_build', category: 'mining', shape: 'statement', json: { block: 'build' },
    message: 'build schematic {schematic} at x {x} y {y} z {z}',
    args: { schematic: str('schematic', 'house.schem'), x: num('x', 0), y: num('y', 64), z: num('z', 0) },
    tooltip: "Builds a schematic from Alto's schematics folder with its origin at the position. Plain file names only (no folders).",
  },
  {
    type: 'alto_fill', category: 'mining', shape: 'statement', json: { block: 'fill' },
    message: 'fill from x {x1} y {y1} z {z1} to x {x2} y {y2} z {z2} with {material}',
    args: {
      material: idv('material', 'block', 'minecraft:stone'),
      x1: num('x1', 0), y1: num('y1', 64), z1: num('z1', 0), x2: num('x2', 4), y2: num('y2', 64), z2: num('z2', 4),
    },
    tooltip: 'Fills the box between two opposite corners (inclusive) with a block. More than 100,000 blocks gets a warning.',
  },
  {
    type: 'alto_farm', category: 'mining', shape: 'statement', json: { block: 'farm' }, dynamic: true,
    message: 'harvest and replant crops nearby', args: {},
    searchWords: 'farm crops range harvest replant',
    tooltip: "One pass of harvesting and replanting nearby crops. Choose a range (1 to 256 blocks) or Alto's default, and optionally only some crops.",
  },
  {
    type: 'alto_use_item_on_block', category: 'mining', shape: 'statement', json: { block: 'use_item_on_block' },
    message: 'holding {item} right-click the {face} side of the block at x {x} y {y} z {z}',
    args: { item: idv('item', 'item', 'minecraft:bone_meal'), x: num('x', 0), y: num('y', 64), z: num('z', 0), face: choice('face', FACE_OPTIONS) },
    tooltip: 'Right-clicks one side of the block at the position while holding the item.',
  },

  // ---------------------------------------------------------------- crafting & items
  {
    type: 'alto_craft', category: 'crafting', shape: 'statement', json: { block: 'craft' },
    message: 'craft {count} more {item}', args: { item: idv('item', 'item', 'minecraft:oak_planks'), count: num('count', 16) },
    tooltip: "Crafts this many MORE of the item (Alto's planner picks recipes and stations). Fails when materials are missing.",
  },
  {
    type: 'alto_smelt', category: 'crafting', shape: 'statement', json: { block: 'smelt' },
    message: 'smelt until {count} more {item} are made', args: { item: idv('item', 'item', 'minecraft:iron_ingot'), count: num('count', 8) },
    tooltip: 'Smelts until this many more of the RESULT item (e.g. iron_ingot) have been made.',
  },
  {
    type: 'alto_eat', category: 'crafting', shape: 'statement', json: { block: 'eat' },
    message: 'eat until not hungry', args: {},
    tooltip: 'Eats food from the inventory until no longer hungry (does nothing when not hungry).',
  },
  {
    type: 'alto_place_station', category: 'crafting', shape: 'statement', json: { block: 'place_station' },
    message: 'place a {kind} from my inventory', args: { kind: choice('kind', STATION_OPTIONS) },
    tooltip: 'Places a work station from the inventory next to you.',
  },
  {
    type: 'alto_pickup_station', category: 'crafting', shape: 'statement', json: { block: 'pickup_station' },
    message: 'pick up the {kind} Alto placed', args: { kind: choice('kind', STATION_OPTIONS) },
    tooltip: 'Picks up a work station that Alto placed (never one you built).',
  },

  // ---------------------------------------------------------------- storage
  {
    type: 'alto_fetch', category: 'storage', shape: 'statement', json: { block: 'fetch' },
    message: 'take {count} {item} out of storage', args: { item: idv('item', 'item', 'minecraft:bread'), count: num('count', 16) },
    tooltip: 'Takes this many of the item out of storage Alto knows.',
  },
  {
    type: 'alto_deposit', category: 'storage', shape: 'statement', json: { block: 'deposit' }, dynamic: true,
    message: 'put my inventory into storage', args: {},
    searchWords: 'deposit store except only keep',
    tooltip: 'Puts inventory items into storage. Equipped armor, the offhand and tools in use always stay. Use + to keep some items or to put only some items.',
  },

  // ---------------------------------------------------------------- create
  {
    type: 'alto_set_filter', category: 'create', shape: 'statement', json: { block: 'set_filter' },
    message: 'set the filter of the block at x {x} y {y} z {z} {face} to {item}',
    args: {
      x: num('x', 0), y: num('y', 64), z: num('z', 0),
      face: choice('face', [['on its filter side', ''], ...FACE_OPTIONS.map(([l, v]) => ['on the ' + l + ' side', v])], { omitValue: '' }),
      item: idv('item', 'item', 'minecraft:iron_ingot'),
    },
    tooltip: 'Sets the filter of a block (e.g. a Create deployer or funnel) to the item. "On its filter side" lets Alto use the side that has the filter slot.',
  },

  // ---------------------------------------------------------------- vehicles & portals
  {
    type: 'alto_board_vehicle', category: 'vehicles', shape: 'statement', json: { block: 'board_vehicle' },
    message: 'get into a {kind}', args: { kind: choice('kind', VEHICLE_OPTIONS) },
    tooltip: 'Boards a vehicle close by: a boat, a minecart, a seat on a Create train, or the nearest rideable vehicle.',
  },
  {
    type: 'alto_ride_train', category: 'vehicles', shape: 'statement', json: { block: 'ride_train' },
    message: 'ride the train from station {from_station} to station {to_station}',
    args: { from_station: str('from_station', 'Home'), to_station: str('to_station', 'Mine') },
    tooltip: 'Rides a Create train from one named station to another (names 1 to 64 characters).',
  },
  {
    type: 'alto_leave_vehicle', category: 'vehicles', shape: 'statement', json: { block: 'leave_vehicle' },
    message: 'get out of the vehicle', args: {}, tooltip: 'Gets out of the current vehicle.',
  },
  {
    type: 'alto_take_portal', category: 'vehicles', shape: 'statement', json: { block: 'take_portal' },
    message: 'go through the portal', args: {},
    tooltip: 'Goes through the portal next to you (or the nearest known one) and waits for the new dimension.',
  },

  // ---------------------------------------------------------------- control
  {
    type: 'alto_wait', category: 'control', shape: 'statement', json: { block: 'wait' },
    message: 'wait {seconds} seconds', args: { seconds: num('seconds', 1) },
    tooltip: 'Waits (0 to 86400 seconds; 0 waits until the next tick). Gives the game time, so loops may contain it.',
  },
  {
    type: 'alto_wait_until', category: 'control', shape: 'statement', json: { block: 'wait_until' },
    message: 'wait until {condition}', args: { condition: cond('condition') },
    tooltip: 'Waits until the condition is true (checked every tick). Continues at once when it already is.',
  },
  {
    type: 'alto_wait_until_timeout', category: 'control', shape: 'statement', json: { block: 'wait_until', timeout: true },
    message: 'wait until {condition} or fail after {timeout_seconds} seconds',
    args: { condition: cond('condition'), timeout_seconds: num('timeout_seconds', 30) },
    tooltip: 'Waits until the condition is true. The whole run FAILS when it is still false after the timeout (0 to 86400 seconds).',
  },
  {
    type: 'alto_repeat', category: 'control', shape: 'c', json: { block: 'repeat' },
    message: 'repeat {times} times', args: { times: num('times', 10), body: stmts('body') },
    tooltip: 'Runs the blocks inside this many times (a whole number 0 to 100000). The inside needs a wait or an action.',
  },
  {
    type: 'alto_repeat_until', category: 'control', shape: 'c', json: { block: 'repeat_until' },
    message: 'repeat until {condition}', args: { condition: cond('condition'), body: stmts('body') },
    tooltip: 'Checks the condition before every pass and stops as soon as it is true. The inside needs a wait or an action.',
  },
  {
    type: 'alto_forever', category: 'control', shape: 'c', json: { block: 'forever' },
    message: 'forever', args: { body: stmts('body') },
    tooltip: "Repeats until break or stop. Alto only starts it when safety stops are enabled in its settings. The inside needs a wait or an action.",
  },
  {
    type: 'alto_if', category: 'control', shape: 'c', json: { block: 'if' },
    message: 'if {condition} then', args: { condition: cond('condition'), then: stmts('then') },
    tooltip: 'Runs the blocks inside when the condition is true.',
  },
  {
    type: 'alto_if_else', category: 'control', shape: 'c', json: { block: 'if', else: true },
    message: 'if {condition} then', elseLabel: 'else',
    args: { condition: cond('condition'), then: stmts('then'), else: stmts('else') },
    tooltip: 'Runs the first blocks when the condition is true, otherwise the second ones.',
  },
  {
    type: 'alto_break', category: 'control', shape: 'statement', json: { block: 'break' },
    message: 'break out of the loop', args: {},
    tooltip: 'Leaves the innermost repeat, repeat until or forever. Must be inside a loop of the same event or custom block.',
  },
  {
    type: 'alto_stop', category: 'control', shape: 'statement', json: { block: 'stop' },
    message: 'stop the script', args: {},
    tooltip: 'Ends the script (status finished) and cancels any action that is still running, also in other handlers.',
  },
  {
    type: 'alto_message', category: 'control', shape: 'statement', json: { block: 'message' },
    message: 'show me the message {text}', args: { text: anyv('text', 'Hello!') },
    tooltip: 'Shows a message to you only; it is never sent to the server. Longer than 1000 characters is cut.',
  },
  {
    type: 'alto_send_chat', category: 'control', shape: 'statement', json: { block: 'send_chat' }, safety: true,
    message: '⚠ send chat as me {text}', args: { text: str('text', 'Hello everyone') },
    tooltip: 'Sends a REAL chat message as you (text starting with / is run by the server as a command). Needs the setting that allows scripts to chat; otherwise the run fails. At most one message per second.',
  },
  {
    type: 'alto_command', category: 'control', shape: 'statement', json: { block: 'command' }, safety: true,
    message: '⚠ run Baritone command {command}', args: { command: text('command', 'mine diamond_ore') },
    tooltip: "Runs a Baritone command such as 'mine diamond_ore' (a leading # is ignored). It only runs when it is on the allowed commands list in Alto's settings; otherwise the run fails. Not a server command.",
  },

  // ---------------------------------------------------------------- values: literals
  {
    type: 'alto_number', category: 'values', shape: 'reporter', output: NUM, json: { literal: 'number' }, style: 'literal',
    message: '{value}', args: { value: { kind: 'numfield', default: 0 } }, tooltip: 'A number.',
  },
  {
    type: 'alto_text', category: 'values', shape: 'reporter', output: STR, json: { literal: 'string' }, style: 'literal',
    message: '{value}', args: { value: { kind: 'textfield', default: '' } }, tooltip: 'A text.',
  },
  {
    type: 'alto_boolean', category: 'values', shape: 'reporter', output: BOOL, json: { literal: 'boolean' },
    message: '{value}', args: { value: { kind: 'boolfield' } }, tooltip: 'true or false.',
  },
  {
    type: 'alto_item', category: 'values', shape: 'reporter', output: STR, json: { literal: 'string', registry: 'item' }, style: 'literal',
    message: '{value}', args: { value: { kind: 'idfield', registry: 'item', default: 'minecraft:oak_planks' } },
    tooltip: 'An item id. Click to type or search by name (load the registry from the game for modded items).',
  },
  {
    type: 'alto_block', category: 'values', shape: 'reporter', output: STR, json: { literal: 'string', registry: 'block' }, style: 'literal',
    message: '{value}', args: { value: { kind: 'idfield', registry: 'block', default: 'minecraft:stone' } },
    tooltip: 'A block id. Click to type or search by name.',
  },
  {
    type: 'alto_id', category: 'values', shape: 'reporter', output: STR, json: { literal: 'string', registry: 'any' }, style: 'literal',
    message: '{value}', args: { value: { kind: 'idfield', registry: 'any', default: 'minecraft:coal_ore' } },
    tooltip: 'An item or block id. Click to type or search by name.',
  },

  // ---------------------------------------------------------------- values: state (sensing)
  {
    type: 'alto_item_count', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'item_count' }, style: 'sensing',
    message: 'number of {item} in my {where}', args: { item: idv('item', 'item', 'minecraft:oak_planks'), where: choice('where', WHERE_OPTIONS) },
    tooltip: 'How many of the item are in the inventory, or in the storage containers Alto knows.',
  },
  { type: 'alto_health', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'health' }, style: 'sensing', message: 'my health', args: {}, tooltip: 'Health points, 20 = full (2 = one heart).' },
  { type: 'alto_hunger', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'hunger' }, style: 'sensing', message: 'my food level', args: {}, tooltip: 'Food level 0 to 20.' },
  {
    type: 'alto_position', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'position' }, style: 'sensing',
    message: 'my {axis} position', args: { axis: choice('axis', [['x', 'x'], ['y', 'y'], ['z', 'z']]) }, tooltip: 'Your block position on one axis.',
  },
  { type: 'alto_time_of_day', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'time_of_day' }, style: 'sensing', message: 'time of day', args: {}, tooltip: 'Day time in ticks 0 to 23999 (0 sunrise, 6000 noon, 18000 midnight).' },
  { type: 'alto_dimension', category: 'values', shape: 'reporter', output: STR, json: { expr: 'dimension' }, style: 'sensing', message: 'current dimension', args: {}, tooltip: 'The dimension id, e.g. minecraft:overworld.' },
  {
    type: 'alto_block_at', category: 'values', shape: 'reporter', output: STR, json: { expr: 'block_at' }, style: 'sensing',
    message: 'block at x {x} y {y} z {z}', args: { x: num('x', 0), y: num('y', 64), z: num('z', 0) },
    tooltip: 'The block id at the position (full id such as minecraft:stone; minecraft:air for air or unloaded chunks).',
  },
  { type: 'alto_inventory_full', category: 'values', shape: 'reporter', output: BOOL, json: { expr: 'inventory_full' }, style: 'sensing', message: 'my inventory is full', args: {}, tooltip: 'True when no inventory slot is free.' },
  {
    type: 'alto_random', category: 'values', shape: 'reporter', output: NUM, json: { expr: 'random' },
    message: 'random number from {min} to {max}', args: { min: num('min', 1), max: num('max', 6) },
    tooltip: 'A random whole number from min to max inclusive (bounds within -1,000,000,000 to 1,000,000,000).',
  },

  // ---------------------------------------------------------------- values: math, comparisons, logic, text
  ...[
    ['add', '{a} + {b}', 'Adds two numbers.'],
    ['sub', '{a} − {b}', 'Subtracts b from a.'],
    ['mul', '{a} × {b}', 'Multiplies two numbers.'],
    ['div', '{a} ÷ {b}', 'Divides a by b (dividing by zero fails the run).'],
    ['mod', '{a} mod {b}', 'Floored remainder: the result has the sign of b (-1 mod 5 = 4).'],
    ['min', 'smaller of {a} and {b}', 'The smaller of two numbers.'],
    ['max', 'larger of {a} and {b}', 'The larger of two numbers.'],
  ].map(([op, message, tooltip]) => ({
    type: 'alto_' + op, category: 'values', shape: 'reporter', output: NUM, json: { expr: op },
    message, args: { a: num('a', 0), b: num('b', 0) }, tooltip, searchWords: 'math ' + op,
  })),
  ...[
    ['abs', 'absolute value of {a}', 'The number without its sign.'],
    ['round', 'round {a}', 'Rounds half up (2.5 becomes 3, -2.5 becomes -2).'],
  ].map(([op, message, tooltip]) => ({
    type: 'alto_' + op, category: 'values', shape: 'reporter', output: NUM, json: { expr: op },
    message, args: { a: num('a', 0) }, tooltip, searchWords: 'math ' + op,
  })),
  ...[
    ['lt', '{a} < {b}', 'True when a is less than b.'],
    ['lte', '{a} ≤ {b}', 'True when a is less than or equal to b.'],
    ['gt', '{a} > {b}', 'True when a is greater than b.'],
    ['gte', '{a} ≥ {b}', 'True when a is greater than or equal to b.'],
  ].map(([op, message, tooltip]) => ({
    type: 'alto_' + op, category: 'values', shape: 'reporter', output: BOOL, json: { expr: op },
    message, args: { a: num('a', 0), b: num('b', 0) }, tooltip, searchWords: 'compare comparison ' + op,
  })),
  ...[
    ['eq', '{a} = {b}', 'True when both values are equal. Both sides must have the same type (numbers, texts or true/false); texts are compared exactly.'],
    ['neq', '{a} ≠ {b}', 'True when the values differ. Both sides must have the same type.'],
  ].map(([op, message, tooltip]) => ({
    type: 'alto_' + op, category: 'values', shape: 'reporter', output: BOOL, json: { expr: op },
    message, args: { a: anyv('a', ''), b: anyv('b', '') }, tooltip, searchWords: 'compare equals equal not ' + op,
  })),
  {
    type: 'alto_and', category: 'values', shape: 'reporter', output: BOOL, json: { expr: 'and' },
    message: '{a} and {b}', args: { a: cond('a'), b: cond('b') }, tooltip: 'True when both are true (b is not checked when a is false).', searchWords: 'logic',
  },
  {
    type: 'alto_or', category: 'values', shape: 'reporter', output: BOOL, json: { expr: 'or' },
    message: '{a} or {b}', args: { a: cond('a'), b: cond('b') }, tooltip: 'True when at least one is true (b is not checked when a is true).', searchWords: 'logic',
  },
  {
    type: 'alto_not', category: 'values', shape: 'reporter', output: BOOL, json: { expr: 'not' },
    message: 'not {a}', args: { a: cond('a') }, tooltip: 'True when the condition is false.', searchWords: 'logic',
  },
  {
    type: 'alto_join', category: 'values', shape: 'reporter', output: STR, json: { expr: 'join' }, dynamic: true,
    message: 'join', args: {}, tooltip: 'Joins the shown form of every part into one text (whole numbers without .0). Use + and − to change the number of parts.',
    searchWords: 'join text concatenate string',
  },

  // ---------------------------------------------------------------- variables
  ...[['number', NUM, 'alto_number', 0], ['string', STR, 'alto_text', ''], ['boolean', BOOL, 'alto_boolean', 'TRUE']].flatMap(([t, check, shadow, def]) => [
    {
      type: 'alto_var_' + t, category: 'variables', shape: 'reporter', output: check, json: { expr: 'var', varType: check },
      message: '{name}', args: { name: { kind: 'variable', varType: check } },
      tooltip: `The value of a ${t === 'string' ? 'text' : t} variable.`,
    },
    {
      type: 'alto_set_' + t, category: 'variables', shape: 'statement', json: { block: 'set_var', varType: check },
      message: 'set {name} to {value}',
      args: { name: { kind: 'variable', varType: check }, value: { kind: 'value', json: 'value', check, shadow, default: def } },
      tooltip: `Sets a ${t === 'string' ? 'text' : t} variable (variables keep the type of their initial value).`,
    },
  ]),
  {
    type: 'alto_change_var', category: 'variables', shape: 'statement', json: { block: 'change_var' },
    message: 'change {name} by {delta}', args: { name: { kind: 'variable', varType: NUM }, delta: num('delta', 1) },
    tooltip: 'Adds a number (may be negative) to a number variable.',
  },

  // ---------------------------------------------------------------- my blocks
  {
    type: 'alto_procedure_def', category: 'procedures', shape: 'hat', json: { procedure: true }, dynamic: true,
    message: 'define', args: {}, tooltip: 'Defines a custom block. The blocks below run when the custom block is used. Right-click to edit.',
  },
  {
    type: 'alto_call', category: 'procedures', shape: 'statement', json: { block: 'call' }, dynamic: true,
    message: '', args: {}, tooltip: 'Runs a custom block.',
  },
  {
    type: 'alto_param', category: 'procedures', shape: 'reporter', output: null, json: { expr: 'param' }, dynamic: true,
    message: '', args: {}, tooltip: 'A parameter of a custom block; only usable inside its definition.',
  },
];

export const SPEC_BY_TYPE = Object.fromEntries(SPECS.map((s) => [s.type, s]));

/** Input/field name in Blockly for a JSON field name. */
export function blocklyName(json) {
  return json.toUpperCase();
}

/** Blockly type of the statement/expression/event spec matching a JSON node (static lookups). */
export const LOOKUP = (() => {
  const events = {};
  const blocks = {};
  const exprs = {};
  for (const s of SPECS) {
    if (s.json.event) events[s.json.event] = s;
    else if (s.json.block && !s.json.varType && !s.json.target && !s.json.else && !s.json.timeout) blocks[s.json.block] = s;
    else if (s.json.expr && s.json.expr !== 'var') exprs[s.json.expr] = s;
  }
  return { events, blocks, exprs };
})();

export const SHADOW_FIELD = { alto_number: 'VALUE', alto_text: 'VALUE', alto_boolean: 'VALUE', alto_item: 'VALUE', alto_block: 'VALUE', alto_id: 'VALUE' };

/** The block state of a literal shadow/block. */
export function literalState(type, value) {
  if (type === 'alto_boolean') return { type, fields: { VALUE: value === true || value === 'TRUE' ? 'TRUE' : 'FALSE' } };
  return { type, fields: { VALUE: value } };
}

/**
 * Words describing a block for the palette search: primary = its label and names, secondary = its category and
 * tooltip (weaker matches, listed after the direct ones).
 */
export function searchText(spec) {
  const primary = [spec.type.replace(/^alto_/, '').replace(/_/g, ' '), spec.message.replace(/{[a-z_0-9]+}/g, ' '), spec.searchWords || ''];
  const j = spec.json;
  for (const k of ['block', 'expr', 'event', 'target']) if (typeof j[k] === 'string') primary.push(j[k], j[k].replace(/_/g, ' '));
  const category = CATEGORIES.find(([key]) => key === spec.category);
  const secondary = [spec.tooltip || '', category ? category[1] : ''];
  return { primary: primary.join(' ').toLowerCase(), secondary: secondary.join(' ').toLowerCase() };
}

/** Whether every query word starts a word of the text ("craft" matches "crafts", not "minecraft"). */
export function matchesWords(text, words) {
  const tokens = text.split(/[^a-z0-9#!/]+/).filter(Boolean);
  return words.every((w) => tokens.some((t) => t.startsWith(w)));
}
