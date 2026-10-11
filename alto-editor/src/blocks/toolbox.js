// The toolbox (palette): categories with presets generated from specs.js, dynamic Variables / My Blocks flyouts
// and the search results flyout.

import { CATEGORIES, SPEC_BY_TYPE, SPECS, blocklyName, literalState, matchesWords, searchText } from './specs.js';
import { PARAM_LABEL, paramShadow } from './dynamic.js';

/** A toolbox entry for a static block with its default shadows; overrides = {fields, inputs, extraState}. */
export function blockItem(type, overrides = {}) {
  const spec = SPEC_BY_TYPE[type];
  const item = { kind: 'block', type };
  const inputs = {};
  for (const [name, arg] of Object.entries(spec.args)) {
    if (arg.kind === 'value' && arg.shadow) inputs[blocklyName(name)] = { shadow: literalState(arg.shadow, arg.default) };
  }
  Object.assign(inputs, overrides.inputs || {});
  if (Object.keys(inputs).length) item.inputs = inputs;
  if (overrides.fields) item.fields = overrides.fields;
  if (overrides.extraState) item.extraState = overrides.extraState;
  return item;
}

const label = (text) => ({ kind: 'label', text });
const sep = (gap = 24) => ({ kind: 'sep', gap: String(gap) });
const shadowOf = (type, value) => ({ shadow: literalState(type, value) });

/** Static category contents. */
export function staticCategories() {
  return {
    events: [
      blockItem('alto_on_start'), blockItem('alto_on_chat'), blockItem('alto_on_inventory_full'),
      blockItem('alto_on_health_below'), blockItem('alto_on_item_below'), blockItem('alto_every'),
    ],
    movement: [
      blockItem('alto_goto_coords'), blockItem('alto_goto_home'), blockItem('alto_goto_storage_zone'), blockItem('alto_goto_portal'),
      sep(), blockItem('alto_position'),
    ],
    mining: [
      blockItem('alto_mine'), blockItem('alto_build'), blockItem('alto_fill'),
      blockItem('alto_farm', { extraState: { crops: 0 } }),
      blockItem('alto_farm', { fields: { RANGE_MODE: 'SET' }, extraState: { crops: 1 }, inputs: { RANGE: shadowOf('alto_number', 24), CROP0: shadowOf('alto_id', 'minecraft:sugar_cane') } }),
      blockItem('alto_use_item_on_block'),
      sep(), blockItem('alto_block_at'),
    ],
    crafting: [
      blockItem('alto_craft'), blockItem('alto_smelt'), blockItem('alto_place_station'), blockItem('alto_pickup_station'),
      blockItem('alto_eat'), sep(), blockItem('alto_item_count'),
    ],
    storage: [
      blockItem('alto_fetch'),
      blockItem('alto_deposit', { extraState: { except: 0, only: 0 } }),
      blockItem('alto_deposit', { extraState: { except: 2, only: 0 }, inputs: { EXCEPT0: shadowOf('alto_item', 'minecraft:bread'), EXCEPT1: shadowOf('alto_item', 'minecraft:torch') } }),
      sep(), blockItem('alto_item_count', { fields: { WHERE: 'storage' } }),
    ],
    create: [
      label('Create mod'),
      blockItem('alto_set_filter'), blockItem('alto_ride_train'), blockItem('alto_board_vehicle', { fields: { KIND: 'train_seat' } }),
    ],
    vehicles: [
      blockItem('alto_board_vehicle'), blockItem('alto_leave_vehicle'), blockItem('alto_ride_train'),
      sep(), blockItem('alto_goto_portal'), blockItem('alto_take_portal'),
    ],
    control: [
      blockItem('alto_wait'), blockItem('alto_wait_until'), blockItem('alto_wait_until_timeout'), sep(),
      blockItem('alto_repeat'), blockItem('alto_repeat_until'), blockItem('alto_forever'), sep(),
      blockItem('alto_if'), blockItem('alto_if_else'), sep(),
      blockItem('alto_break'), blockItem('alto_stop'), sep(),
      label('Messages (only you see them)'),
      blockItem('alto_message'),
      label('Need a setting in Alto, otherwise the run fails'),
      blockItem('alto_send_chat'), blockItem('alto_command'),
    ],
    values: [
      label('Values'),
      blockItem('alto_number'), blockItem('alto_text'), blockItem('alto_boolean'),
      blockItem('alto_item'), blockItem('alto_block'),
      label('Math'),
      ...['add', 'sub', 'mul', 'div', 'mod', 'min', 'max', 'abs', 'round'].map((op) => blockItem('alto_' + op)),
      blockItem('alto_random'),
      label('Compare'),
      ...['lt', 'lte', 'gt', 'gte'].map((op) => blockItem('alto_' + op)),
      blockItem('alto_eq', { inputs: { A: shadowOf('alto_number', 0), B: shadowOf('alto_number', 0) } }),
      blockItem('alto_eq', { inputs: { A: shadowOf('alto_text', ''), B: shadowOf('alto_text', '') } }),
      blockItem('alto_neq', { inputs: { A: shadowOf('alto_number', 0), B: shadowOf('alto_number', 0) } }),
      blockItem('alto_neq', { inputs: { A: shadowOf('alto_text', ''), B: shadowOf('alto_text', '') } }),
      label('Logic'),
      blockItem('alto_and'), blockItem('alto_or'), blockItem('alto_not'),
      label('Text'),
      blockItem('alto_join', { extraState: { parts: 2 }, inputs: { PART0: shadowOf('alto_text', 'Pass '), PART1: shadowOf('alto_text', 'done') } }),
      label('Player and world'),
      blockItem('alto_health'), blockItem('alto_hunger'), blockItem('alto_position'), blockItem('alto_time_of_day'),
      blockItem('alto_dimension'), blockItem('alto_inventory_full'), blockItem('alto_item_count'), blockItem('alto_block_at'),
    ],
  };
}

const VAR_TYPE_NAME = { Number: 'number', String: 'text', Boolean: 'true/false' };
const VAR_TYPE_KEY = { Number: 'number', String: 'string', Boolean: 'boolean' };

/** Variables flyout: make-a-variable button, a reporter per variable, set/change blocks. */
export function variablesFlyout(variables) {
  const out = [{ kind: 'button', text: 'Make a variable', callbackKey: 'ALTO_CREATE_VARIABLE' }];
  if (!variables.length) {
    out.push(label('Variables keep the type of their initial value.'));
    return out;
  }
  for (const v of variables) {
    out.push({ kind: 'block', type: 'alto_var_' + VAR_TYPE_KEY[v.type], fields: { NAME: { id: v.id } } });
  }
  out.push(sep());
  for (const type of ['Number', 'String', 'Boolean']) {
    const first = variables.find((v) => v.type === type);
    if (!first) continue;
    out.push(blockItem('alto_set_' + VAR_TYPE_KEY[type], { fields: { NAME: { id: first.id } } }));
    if (type === 'Number') out.push(blockItem('alto_change_var', { fields: { NAME: { id: first.id } } }));
  }
  out.push(label('Types: ' + [...new Set(variables.map((v) => VAR_TYPE_NAME[v.type]))].join(', ')));
  return out;
}

/** The toolbox entry for a call of a procedure, with default argument shadows. */
export function callItem(proc) {
  const inputs = {};
  for (const p of proc.params) {
    const shadow = paramShadow(p.type);
    if (shadow) inputs['ARG_' + p.name] = { shadow };
  }
  const item = { kind: 'block', type: 'alto_call', extraState: { name: proc.name, params: proc.params } };
  if (Object.keys(inputs).length) item.inputs = inputs;
  return item;
}

/** My Blocks flyout: make-a-block button, then for every custom block its call and its parameter reporters. */
export function proceduresFlyout(procedures) {
  const out = [{ kind: 'button', text: 'Make a block', callbackKey: 'ALTO_CREATE_PROCEDURE' }];
  if (!procedures.length) {
    out.push(label('Custom blocks run their definition when used (like Scratch "My Blocks").'));
    return out;
  }
  for (const proc of procedures) {
    out.push(callItem(proc));
    if (proc.params.length) {
      out.push(label(`parameters of ${proc.name} (use inside its definition):`));
      for (const p of proc.params) {
        out.push({ kind: 'block', type: 'alto_param', extraState: { name: p.name, type: p.type } });
      }
    }
    out.push(sep(32));
  }
  out.push(label('Right-click a "define" block to edit it.'));
  return out;
}

/** Search text for a toolbox entry. */
function entrySearchText(item, extra = '', extraSecondary = '') {
  const spec = SPEC_BY_TYPE[item.type];
  const t = spec ? searchText(spec) : { primary: item.type, secondary: '' };
  return { primary: (t.primary + ' ' + extra).toLowerCase(), secondary: (t.secondary + ' ' + extraSecondary).toLowerCase() };
}

/**
 * Palette search: every block whose label, name, JSON name or tooltip contains all words of the query.
 * dynamic = {variables, procedures} for the Variables and My Blocks entries.
 */
export function searchFlyout(query, dynamic) {
  const words = String(query || '').toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return [label('Type in the search box above to find blocks by name.')];
  const entries = [];
  const seen = new Set();
  const cats = staticCategories();
  for (const [key, labelText] of CATEGORIES) {
    for (const item of cats[key] || []) {
      if (item.kind !== 'block') continue;
      const signature = JSON.stringify(item);
      if (seen.has(signature)) continue;
      seen.add(signature);
      entries.push([item, entrySearchText(item, '', labelText)]);
    }
  }
  for (const item of variablesFlyout(dynamic.variables)) {
    if (item.kind !== 'block') continue;
    const v = dynamic.variables.find((x) => x.id === item.fields.NAME.id);
    entries.push([item, entrySearchText(item, 'variable ' + (v ? v.name : ''))]);
  }
  for (const proc of dynamic.procedures) {
    entries.push([callItem(proc), entrySearchText({ type: 'alto_call' }, 'my block custom call ' + proc.name)]);
    for (const p of proc.params) {
      entries.push([{ kind: 'block', type: 'alto_param', extraState: { name: p.name, type: p.type } }, { primary: `parameter ${p.name} ${PARAM_LABEL[p.type] || ''} ${proc.name}`.toLowerCase(), secondary: '' }]);
    }
  }
  const direct = entries.filter(([, t]) => matchesWords(t.primary, words)).map(([item]) => item);
  const related = entries.filter(([, t]) => !matchesWords(t.primary, words) && matchesWords(t.primary + ' ' + t.secondary, words)).map(([item]) => item);
  if (!direct.length && !related.length) return [label(`No block matches "${query}".`)];
  const out = [];
  if (direct.length) out.push(label(`${direct.length} block${direct.length === 1 ? '' : 's'} found:`), ...direct.slice(0, 60));
  if (related.length) out.push(label(direct.length ? 'Related blocks:' : 'Related blocks (category or description):'), ...related.slice(0, 40));
  return out;
}

/** The category toolbox definition. */
export function toolboxDefinition() {
  const cats = staticCategories();
  const contents = [{ kind: 'category', name: 'Search results', categorystyle: 'search_category', custom: 'ALTO_SEARCH', toolboxitemid: 'search' }];
  for (const [key, name] of CATEGORIES) {
    if (key === 'variables') contents.push({ kind: 'category', name, categorystyle: 'variables_category', custom: 'ALTO_VARIABLES', toolboxitemid: key });
    else if (key === 'procedures') contents.push({ kind: 'category', name, categorystyle: 'procedures_category', custom: 'ALTO_PROCEDURES', toolboxitemid: key });
    else contents.push({ kind: 'category', name, categorystyle: key + '_category', contents: cats[key], toolboxitemid: key });
  }
  return { kind: 'categoryToolbox', contents };
}

/** Every block type that appears somewhere in the palette (for tests). */
export function paletteTypes() {
  const types = new Set();
  for (const items of Object.values(staticCategories())) for (const i of items) if (i.kind === 'block') types.add(i.type);
  for (const t of ['alto_var_number', 'alto_var_string', 'alto_var_boolean', 'alto_set_number', 'alto_set_string', 'alto_set_boolean', 'alto_change_var', 'alto_call', 'alto_param', 'alto_procedure_def']) types.add(t);
  return types;
}

export { SPECS };
