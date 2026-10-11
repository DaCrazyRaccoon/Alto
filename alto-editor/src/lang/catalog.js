// The Alto script language catalog, mirroring baritone.alto.script.lang (ActionType, Fields, Limits, the enums).
// Pure data plus the FieldSpec checks; no DOM and no Blockly, so Node tests and the browser share it.

import { formatNumber, isWhole, javaIsBlank, javaTrim } from './javaish.js';

export const FORMAT = 'alto-script';
export const CURRENT_VERSION = 1;

export const LIMITS = Object.freeze({
  MAX_COUNT: 100000,
  MAX_REPEAT: 100000,
  LARGE_COUNT: 10000,
  LARGE_FILL_VOLUME: 100000,
  MAX_WAIT_SECONDS: 86400,
  MIN_EVERY_SECONDS: 1,
  MAX_COORDINATE: 30000000,
  MAX_RANDOM: 1000000000,
  MAX_FARM_RANGE: 256,
  MAX_HEALTH: 1024,
  MAX_CHAT_LENGTH: 256,
  MAX_TRIGGER_LENGTH: 64,
  MAX_NAME_LENGTH: 64,
  MAX_TEXT_LENGTH: 1000,
  MAX_SCRIPT_NAME_LENGTH: 100,
  MAX_DESCRIPTION_LENGTH: 4000,
  MAX_STRING_VALUE: 10000,
  MAX_SCRIPT_CHARS: 1000000,
  MAX_JSON_DEPTH: 100,
  MAX_NODES: 20000,
});

// ------------------------------------------------------------------------------------------ value types

export const NUMBER = 'number';
export const STRING = 'string';
export const BOOLEAN = 'boolean';

export function withArticle(type) {
  return 'a ' + type;
}

export const PARAM_TYPES = ['number', 'string', 'boolean', 'item', 'block'];
export function paramValueType(paramType) {
  return paramType === 'number' ? NUMBER : paramType === 'boolean' ? BOOLEAN : STRING;
}
export function paramIsId(paramType) {
  return paramType === 'item' || paramType === 'block';
}

export const WHERE = ['inventory', 'storage'];
export const AXES = ['x', 'y', 'z'];
export const FACES = ['up', 'down', 'north', 'south', 'east', 'west'];
export const STATIONS = ['crafting_table', 'furnace', 'blast_furnace', 'smoker'];
export const VEHICLES = ['boat', 'minecart', 'train_seat', 'nearest'];
export const TARGET_TYPES = ['coords', 'home', 'storage_zone', 'portal'];
export const EVENT_TYPES = ['on_start', 'on_chat', 'on_inventory_full', 'on_health_below', 'on_item_below', 'every'];

/** Sensors: expressions without fields and their static types. */
export const SENSORS = { health: NUMBER, hunger: NUMBER, time_of_day: NUMBER, dimension: STRING, inventory_full: BOOLEAN };

/** Binary operators: category decides operand and result types. */
export const BINARY_OPS = {
  add: 'math', sub: 'math', mul: 'math', div: 'math', mod: 'math', min: 'math', max: 'math',
  eq: 'equality', neq: 'equality',
  lt: 'order', lte: 'order', gt: 'order', gte: 'order',
  and: 'logic', or: 'logic',
};
export function binaryOperandType(op) {
  const c = BINARY_OPS[op];
  return c === 'math' || c === 'order' ? NUMBER : c === 'logic' ? BOOLEAN : null;
}
export function binaryResultType(op) {
  return BINARY_OPS[op] === 'math' ? NUMBER : BOOLEAN;
}

/** Unary operators: operand and result type are the same. */
export const UNARY_OPS = { abs: NUMBER, round: NUMBER, not: BOOLEAN };

/** Expr.kinds(): every expr name, in the Java order (used for "did you mean" hints). */
export const EXPR_KINDS = [
  'var', 'param', 'item_count', 'position', 'block_at', 'random', 'join',
  ...Object.keys(SENSORS), ...Object.keys(BINARY_OPS), ...Object.keys(UNARY_OPS),
];

export const CONTROL_BLOCKS = ['repeat', 'repeat_until', 'forever', 'if', 'wait_until', 'break', 'call', 'set_var', 'change_var'];

// ------------------------------------------------------------------------------------------ FieldSpec

/** FieldKind: NUMBER, INTEGER, STRING, ANY, ID, FILE_NAME are expressions; TEXT, CHOICE, ID_LIST, TARGET are not. */
function spec(name, kind, required, min, max, choices = []) {
  return Object.freeze({ name, kind, required, min, max, choices: Object.freeze(choices) });
}
export const F = {
  number: (name, min, max) => spec(name, 'NUMBER', true, min, max),
  integer: (name, min, max) => spec(name, 'INTEGER', true, min, max),
  count: (name) => spec(name, 'INTEGER', true, 1, LIMITS.MAX_COUNT),
  coordinate: (name) => spec(name, 'INTEGER', true, -LIMITS.MAX_COORDINATE, LIMITS.MAX_COORDINATE),
  string: (name, maxLength) => spec(name, 'STRING', true, 0, maxLength),
  name: (name, maxLength) => spec(name, 'STRING', true, 1, maxLength),
  any: (name) => spec(name, 'ANY', true, 0, LIMITS.MAX_TEXT_LENGTH),
  id: (name) => spec(name, 'ID', true, 0, 200),
  fileName: (name) => spec(name, 'FILE_NAME', true, 0, 128),
  literalText: (name, maxLength) => spec(name, 'TEXT', true, 0, maxLength),
  choice: (name, values) => spec(name, 'CHOICE', true, 0, 0, values),
  idList: (name) => spec(name, 'ID_LIST', true, 0, 200),
  target: (name) => spec(name, 'TARGET', true, 0, 0),
  optional: (s) => Object.freeze({ ...s, required: false }),
};

export function isExpressionKind(kind) {
  return ['NUMBER', 'INTEGER', 'STRING', 'ANY', 'ID', 'FILE_NAME'].includes(kind);
}

/** FieldSpec.valueType: the static type an expression in the field must have, or null. */
export function fieldValueType(f) {
  switch (f.kind) {
    case 'NUMBER':
    case 'INTEGER':
      return NUMBER;
    case 'STRING':
    case 'ID':
    case 'FILE_NAME':
    case 'ID_LIST':
      return STRING;
    default:
      return null;
  }
}

// ------------------------------------------------------------------------------------------ ids and names

const ID_RE = /^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$/;
const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;
const FILE_NAME_RE = /^[A-Za-z0-9 _.()+\-]+$/;

export function isValidId(id) {
  return typeof id === 'string' && id.length <= 200 && ID_RE.test(id);
}
export function normalizeId(id) {
  return id.indexOf(':') < 0 ? 'minecraft:' + id : id;
}
export function isValidName(name) {
  return typeof name === 'string' && name.length <= LIMITS.MAX_NAME_LENGTH && NAME_RE.test(name);
}
export function isValidFileName(name) {
  return typeof name === 'string' && name.length > 0 && name.length <= 128 && FILE_NAME_RE.test(name)
    && !name.startsWith('.') && !name.includes('..') && name === javaTrim(name);
}

/** FieldSpec.checkNumber: a {code, message} problem or null. */
export function checkNumber(f, v) {
  if (f.kind === 'INTEGER' && !isWhole(v)) {
    return { code: 'not_whole_number', message: `${f.name} must be a whole number, got ${formatNumber(v)}` };
  }
  if (v < f.min || v > f.max) {
    return {
      code: 'out_of_range',
      message: `${f.name} must be between ${formatNumber(f.min)} and ${formatNumber(f.max)}, got ${formatNumber(v)}`,
    };
  }
  return null;
}

/** FieldSpec.checkText: a {code, message} problem or null. */
export function checkText(f, s) {
  switch (f.kind) {
    case 'ID':
    case 'ID_LIST':
      if (!isValidId(s)) {
        return { code: 'invalid_id', message: `${f.name} must be an item or block id like minecraft:oak_planks, got '${s}'` };
      }
      break;
    case 'FILE_NAME':
      if (!isValidFileName(s)) {
        return {
          code: 'invalid_file_name',
          message: `${f.name} must be a plain file name such as house.schem (letters, digits, spaces and _ . ( ) + -, no folders), got '${s}'`,
        };
      }
      break;
    case 'TEXT':
      if (javaIsBlank(s)) return { code: 'invalid_text', message: `${f.name} must not be empty` };
      break;
    case 'STRING':
      if (f.min >= 1 && javaIsBlank(s)) return { code: 'invalid_text', message: `${f.name} must not be empty` };
      break;
    default:
      break;
  }
  if (s.length > f.max) {
    return { code: 'out_of_range', message: `${f.name} must be at most ${Math.trunc(f.max)} characters, got ${s.length}` };
  }
  return null;
}

// ------------------------------------------------------------------------------------------ actions (ActionType)

/** Action blocks in enum order; fields in canonical order. runner: host = a task in the world. */
export const ACTIONS = [
  { name: 'goto', runner: 'host', fields: [F.target('target')] },
  { name: 'mine', runner: 'host', fields: [F.id('block_or_item'), F.count('count')] },
  { name: 'build', runner: 'host', fields: [F.fileName('schematic'), F.coordinate('x'), F.coordinate('y'), F.coordinate('z')] },
  {
    name: 'fill', runner: 'host',
    fields: [F.id('material'), F.coordinate('x1'), F.coordinate('y1'), F.coordinate('z1'), F.coordinate('x2'), F.coordinate('y2'), F.coordinate('z2')],
  },
  { name: 'farm', runner: 'host', fields: [F.optional(F.integer('range', 1, LIMITS.MAX_FARM_RANGE)), F.optional(F.idList('crops'))] },
  { name: 'craft', runner: 'host', fields: [F.id('item'), F.count('count')] },
  { name: 'smelt', runner: 'host', fields: [F.id('item'), F.count('count')] },
  { name: 'fetch', runner: 'host', fields: [F.id('item'), F.count('count')] },
  { name: 'deposit', runner: 'host', fields: [F.optional(F.idList('except')), F.optional(F.idList('only'))] },
  { name: 'eat', runner: 'host', fields: [] },
  { name: 'wait', runner: 'interpreter', fields: [F.number('seconds', 0, LIMITS.MAX_WAIT_SECONDS)] },
  { name: 'message', runner: 'interpreter', fields: [F.any('text')] },
  { name: 'command', runner: 'host', fields: [F.literalText('command', LIMITS.MAX_CHAT_LENGTH)] },
  { name: 'send_chat', runner: 'host', fields: [F.string('text', LIMITS.MAX_CHAT_LENGTH)] },
  {
    name: 'use_item_on_block', runner: 'host',
    fields: [F.id('item'), F.coordinate('x'), F.coordinate('y'), F.coordinate('z'), F.choice('face', FACES)],
  },
  {
    name: 'set_filter', runner: 'host',
    fields: [F.coordinate('x'), F.coordinate('y'), F.coordinate('z'), F.optional(F.choice('face', FACES)), F.id('item')],
  },
  { name: 'place_station', runner: 'host', fields: [F.choice('kind', STATIONS)] },
  { name: 'pickup_station', runner: 'host', fields: [F.choice('kind', STATIONS)] },
  { name: 'board_vehicle', runner: 'host', fields: [F.choice('kind', VEHICLES)] },
  { name: 'ride_train', runner: 'host', fields: [F.name('from_station', LIMITS.MAX_NAME_LENGTH), F.name('to_station', LIMITS.MAX_NAME_LENGTH)] },
  { name: 'leave_vehicle', runner: 'host', fields: [] },
  { name: 'take_portal', runner: 'host', fields: [] },
  { name: 'stop', runner: 'interpreter', fields: [] },
].map((a) => Object.freeze({ ...a, fields: Object.freeze(a.fields) }));

export const ACTION_BY_NAME = Object.freeze(Object.fromEntries(ACTIONS.map((a) => [a.name, a])));

/** ActionType.yields: every host task and wait give the game time (the loop guard). */
export function actionYields(action) {
  return action.runner === 'host' || action.name === 'wait';
}

/** ActionType.normalizeCommand: trimmed, without Baritone's '#' prefix. */
export function normalizeCommand(raw) {
  let s = javaTrim(raw);
  while (s.startsWith('#')) s = javaTrim(s.substring(1));
  return s;
}

// ------------------------------------------------------------------------------------------ other fields (Fields)

export const FIELDS = Object.freeze({
  REPEAT_TIMES: F.integer('times', 0, LIMITS.MAX_REPEAT),
  TIMEOUT_SECONDS: F.optional(F.number('timeout_seconds', 0, LIMITS.MAX_WAIT_SECONDS)),
  RANDOM_MIN: F.integer('min', -LIMITS.MAX_RANDOM, LIMITS.MAX_RANDOM),
  RANDOM_MAX: F.integer('max', -LIMITS.MAX_RANDOM, LIMITS.MAX_RANDOM),
  BLOCK_X: F.coordinate('x'),
  BLOCK_Y: F.coordinate('y'),
  BLOCK_Z: F.coordinate('z'),
  ITEM: F.id('item'),
  DIMENSION: F.optional(F.id('dimension')),
  PLACE_NAME: F.literalText('name', LIMITS.MAX_NAME_LENGTH),
  EVENT_TRIGGER: F.literalText('trigger', LIMITS.MAX_TRIGGER_LENGTH),
  EVENT_HEALTH: F.number('health', 1, LIMITS.MAX_HEALTH),
  EVENT_ITEM: F.id('item'),
  EVENT_COUNT: F.count('count'),
  EVENT_SECONDS: F.number('seconds', LIMITS.MIN_EVERY_SECONDS, LIMITS.MAX_WAIT_SECONDS),
  SCRIPT_NAME: F.literalText('name', LIMITS.MAX_SCRIPT_NAME_LENGTH),
  SCRIPT_DESCRIPTION: F.optional(F.string('description', LIMITS.MAX_DESCRIPTION_LENGTH)),
});

// ------------------------------------------------------------------------------------------ diagnostics

/** DiagnosticCode in declaration order with severities. */
export const DIAGNOSTIC_CODES = Object.freeze({
  json_syntax: 'error', too_large: 'error', unsupported_format: 'error', unsupported_version: 'error',
  missing_field: 'error', unknown_field: 'error', wrong_json_type: 'error', unknown_block: 'error',
  unknown_expr: 'error', unknown_event: 'error', invalid_choice: 'error',
  invalid_name: 'error', duplicate_name: 'error', duplicate_event: 'error', no_events: 'error',
  unknown_variable: 'error', unknown_procedure: 'error', unknown_param: 'error', param_outside_procedure: 'error',
  missing_argument: 'error', unknown_argument: 'error', type_mismatch: 'error', not_whole_number: 'error',
  out_of_range: 'error', invalid_id: 'error', invalid_file_name: 'error', invalid_text: 'error', recursion: 'error',
  break_outside_loop: 'error', loop_without_wait: 'error',
  forever_loop: 'warning', sends_chat: 'warning', runs_command: 'warning', large_count: 'warning',
  unreachable_code: 'warning', unused_procedure: 'warning', unused_variable: 'warning',
});

export function diagnostic(code, path, message) {
  const severity = DIAGNOSTIC_CODES[code] || 'error';
  return { severity, code, path, message };
}

// ------------------------------------------------------------------------------------------ capabilities

/** Capability in enum order with the sentence shown on the confirmation screen. */
export const CAPABILITIES = Object.freeze([
  ['MOVES', 'walks around'],
  ['BREAKS_BLOCKS', 'breaks blocks'],
  ['PLACES_BLOCKS', 'places blocks'],
  ['USES_ITEMS', 'uses items (right-clicks blocks, eats)'],
  ['CRAFTS', 'crafts items'],
  ['SMELTS', 'smelts items in furnaces'],
  ['MOVES_ITEMS_TO_STORAGE', 'puts items from your inventory into storage'],
  ['TAKES_FROM_STORAGE', 'takes items out of storage'],
  ['TRAVELS_PORTALS', 'walks through portals'],
  ['RIDES_VEHICLES', 'rides boats, minecarts or trains'],
  ['RUNS_COMMANDS', 'runs Baritone commands'],
  ['SENDS_CHAT', 'sends chat messages or server commands as you'],
  ['SETS_FILTERS', 'changes filters on blocks (e.g. Create deployers)'],
  ['RUNS_UNTIL_STOPPED', 'keeps running until you stop it'],
  ['LISTENS_TO_CHAT', 'reacts to chat triggers that you type'],
]);

/** Capability.of(ActionType). */
export const ACTION_CAPABILITIES = Object.freeze({
  goto: ['MOVES'],
  mine: ['MOVES', 'BREAKS_BLOCKS'],
  build: ['MOVES', 'BREAKS_BLOCKS', 'PLACES_BLOCKS'],
  fill: ['MOVES', 'BREAKS_BLOCKS', 'PLACES_BLOCKS'],
  farm: ['MOVES', 'BREAKS_BLOCKS', 'PLACES_BLOCKS'],
  craft: ['MOVES', 'CRAFTS'],
  smelt: ['MOVES', 'SMELTS'],
  fetch: ['MOVES', 'TAKES_FROM_STORAGE'],
  deposit: ['MOVES', 'MOVES_ITEMS_TO_STORAGE'],
  eat: ['USES_ITEMS'],
  use_item_on_block: ['MOVES', 'USES_ITEMS'],
  set_filter: ['MOVES', 'USES_ITEMS', 'SETS_FILTERS'],
  place_station: ['PLACES_BLOCKS'],
  pickup_station: ['BREAKS_BLOCKS'],
  board_vehicle: ['MOVES', 'RIDES_VEHICLES'],
  ride_train: ['MOVES', 'RIDES_VEHICLES'],
  leave_vehicle: ['RIDES_VEHICLES'],
  take_portal: ['MOVES', 'TRAVELS_PORTALS'],
  command: ['RUNS_COMMANDS'],
  send_chat: ['SENDS_CHAT'],
  wait: [],
  message: [],
  stop: [],
});

export const COMPUTED_TEXT = '(text computed while the script runs)';
