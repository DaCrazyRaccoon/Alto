// Script (the parsed AST from lang/reader.js) -> Blockly workspace state (Blockly's JSON serialization) plus the
// editor's own metadata. Literals become shadows where possible so the blocks look like Scratch; a value whose
// type cannot snap into its slot is placed next to the script instead and reported as an import note.

import { SENSORS } from '../lang/catalog.js';
import { LOOKUP, SPEC_BY_TYPE, blocklyName, literalState } from '../blocks/specs.js';
import { PARAM_CHECK, paramShadow } from '../blocks/dynamic.js';

const CHECK_OF_TYPE = { number: 'Number', string: 'String', boolean: 'Boolean' };
const VAR_BLOCK = { Number: 'number', String: 'string', Boolean: 'boolean' };

let idCounter = 0;
function newId(prefix) {
  idCounter = (idCounter + 1) % 1e9;
  return `${prefix}_${Date.now().toString(36)}_${idCounter.toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

/**
 * Converts a parsed script. Returns {state, meta, notes}: state for Blockly.serialization.workspaces.load,
 * meta = {name, description, variables: [{id, initial}]}, notes = [{path, message}] about anything that could
 * not be placed exactly.
 */
export function scriptToWorkspaceState(script) {
  const notes = [];
  const variables = []; // Blockly variable states
  const metaVars = [];
  const varsByName = new Map(); // name -> {id, check}
  const topBlocks = [];
  const detached = [];

  for (const v of script.variables) {
    if (varsByName.has(v.name)) {
      notes.push({ path: v.path, message: `a second variable called '${v.name}' was left out (names must be unique)` });
      continue;
    }
    const id = newId('var');
    const check = CHECK_OF_TYPE[v.initial.type];
    varsByName.set(v.name, { id, check });
    variables.push({ name: v.name, id, type: check });
    metaVars.push({ id, initial: v.initial.value });
  }

  /** A variable reference; undeclared names are created so the blocks can show them. */
  const variableRef = (name, path, wantCheck) => {
    let v = varsByName.get(name);
    if (!v) {
      const check = wantCheck || 'Number';
      const id = newId('var');
      v = { id, check };
      varsByName.set(name, v);
      variables.push({ name, id, type: check });
      const init = check === 'String' ? '' : check === 'Boolean' ? false : 0;
      metaVars.push({ id, initial: init });
      notes.push({ path, message: `variable '${name}' was not declared in the file; the editor added it with the initial value ${JSON.stringify(init)}` });
    }
    return v;
  };

  // ---------------------------------------------------------------------------------------- expressions

  function outputCheck(e, scope) {
    switch (e.kind) {
      case 'literal':
        return CHECK_OF_TYPE[e.value.type];
      case 'var': {
        const v = varsByName.get(e.name);
        return v ? v.check : null;
      }
      case 'param': {
        const decl = scope.procedure ? scope.procedure.params.find((p) => p.name === e.name) : null;
        return decl ? PARAM_CHECK[decl.type] ?? null : null;
      }
      case 'sensor':
        return CHECK_OF_TYPE[SENSORS[e.sensor]];
      case 'item_count':
      case 'position':
      case 'random':
        return 'Number';
      case 'block_at':
      case 'join':
        return 'String';
      case 'binary':
      case 'unary': {
        const spec = LOOKUP.exprs[e.op];
        return spec ? spec.output : null;
      }
      default:
        return null;
    }
  }

  /** Puts an expression into an input described by `slot` ({check, shadow, default}). */
  function input(e, slot, scope, defaultShadow) {
    const shadowDefault = defaultShadow !== undefined ? defaultShadow
      : slot.shadow ? literalState(slot.shadow, slot.default) : null;
    const fits = (check) => slot.check == null || check == null || check === slot.check;
    if (e.kind === 'literal') {
      const check = CHECK_OF_TYPE[e.value.type];
      if (!fits(check)) return detach(e, slot, scope);
      if (e.value.type === 'number') {
        return { shadow: { ...literalState('alto_number', e.value.value), id: newId('s') } };
      }
      if (e.value.type === 'string') {
        const type = slot.shadow === 'alto_item' || slot.shadow === 'alto_block' || slot.shadow === 'alto_id' ? slot.shadow : 'alto_text';
        return { shadow: { ...literalState(type, e.value.value), id: newId('s') } };
      }
      if (slot.shadow === 'alto_boolean') {
        return { shadow: { ...literalState('alto_boolean', e.value.value), id: newId('s') } };
      }
      const out = { block: { ...literalState('alto_boolean', e.value.value), id: newId('b') } };
      if (shadowDefault) out.shadow = { ...shadowDefault, id: newId('s') };
      return out;
    }
    if (!fits(outputCheck(e, scope))) return detach(e, slot, scope);
    const out = { block: expr(e, scope) };
    if (shadowDefault) out.shadow = { ...shadowDefault, id: newId('s') };
    return out;
  }

  function detach(e, slot, scope) {
    const got = outputCheck(e, scope);
    notes.push({
      path: e.path,
      message: `a ${(got || 'value').toLowerCase()} value does not fit this ${slot.check ? slot.check.toLowerCase() : ''} slot; it was placed next to the script`,
    });
    const state = e.kind === 'literal' ? literalState(e.value.type === 'number' ? 'alto_number' : e.value.type === 'string' ? 'alto_text' : 'alto_boolean', e.value.value) : expr(e, scope);
    detached.push({ ...state, id: state.id || newId('b') });
    return null;
  }

  const setInput = (state, name, value) => {
    if (!value) return;
    state.inputs = state.inputs || {};
    state.inputs[name] = value;
  };

  /** A value input of a dynamic block: an empty one is remembered so no default shadow fills it. */
  const setDynamicInput = (state, name, value) => {
    if (value) {
      setInput(state, name, value);
    } else {
      state.extraState.empty = state.extraState.empty || [];
      state.extraState.empty.push(name);
    }
  };

  /** Fills the value inputs and fields of a generic spec from an object of name -> expression/literal. */
  function fillArgs(state, spec, get, scope) {
    for (const [name, arg] of Object.entries(spec.args)) {
      const bn = blocklyName(name);
      const key = arg.json || name;
      const v = get(key);
      switch (arg.kind) {
        case 'value':
          if (v != null) setInput(state, bn, input(v, arg, scope));
          else if (arg.shadow) {
            // a missing value: leave the slot empty so the problem stays visible
          }
          break;
        case 'dropdown':
          state.fields = state.fields || {};
          state.fields[bn] = v == null ? (arg.omitValue !== undefined ? arg.omitValue : arg.options[0][1]) : v;
          break;
        case 'text':
        case 'id':
          state.fields = state.fields || {};
          state.fields[bn] = v == null ? '' : v;
          break;
        case 'number':
          state.fields = state.fields || {};
          state.fields[bn] = v;
          break;
        case 'statements': {
          const first = chain(v || [], scope);
          if (first) setInput(state, bn, { block: first });
          break;
        }
        default:
          break;
      }
    }
    return state;
  }

  function expr(e, scope) {
    const id = newId('b');
    switch (e.kind) {
      case 'var': {
        const v = variableRef(e.name, e.path, null);
        return { type: 'alto_var_' + VAR_BLOCK[v.check], id, fields: { NAME: { id: v.id } } };
      }
      case 'param': {
        const decl = scope.procedure ? scope.procedure.params.find((p) => p.name === e.name) : null;
        return { type: 'alto_param', id, extraState: { name: e.name, type: decl ? decl.type : 'unknown' } };
      }
      case 'sensor':
        return { type: LOOKUP.exprs[e.sensor].type, id };
      case 'position':
        return { type: 'alto_position', id, fields: { AXIS: e.axis } };
      case 'item_count':
        return fillArgs({ type: 'alto_item_count', id }, SPEC_BY_TYPE.alto_item_count, (k) => (k === 'item' ? e.item : e.where), scope);
      case 'block_at':
        return fillArgs({ type: 'alto_block_at', id }, SPEC_BY_TYPE.alto_block_at, (k) => e[k], scope);
      case 'random':
        return fillArgs({ type: 'alto_random', id }, SPEC_BY_TYPE.alto_random, (k) => e[k], scope);
      case 'unary':
        return fillArgs({ type: 'alto_' + e.op, id }, SPEC_BY_TYPE['alto_' + e.op], (k) => e[k], scope);
      case 'binary': {
        const spec = SPEC_BY_TYPE['alto_' + e.op];
        const state = { type: spec.type, id };
        if (e.op === 'eq' || e.op === 'neq') {
          // default shadows follow the other side's type: numbers compare with numbers
          const shadowFor = (other) => (outputCheck(other, scope) === 'Number' ? literalState('alto_number', 0) : literalState('alto_text', ''));
          setInput(state, 'A', input(e.a, spec.args.a, scope, shadowFor(e.b)));
          setInput(state, 'B', input(e.b, spec.args.b, scope, shadowFor(e.a)));
          return state;
        }
        return fillArgs(state, spec, (k) => e[k], scope);
      }
      case 'join': {
        const state = { type: 'alto_join', id, extraState: { parts: e.parts.length } };
        e.parts.forEach((p, i) => setDynamicInput(state, 'PART' + i, input(p, { check: null, shadow: 'alto_text', default: '' }, scope)));
        return state;
      }
      case 'literal': {
        const t = e.value.type === 'number' ? 'alto_number' : e.value.type === 'string' ? 'alto_text' : 'alto_boolean';
        return { ...literalState(t, e.value.value), id };
      }
      default:
        throw new Error('unknown expression ' + e.kind);
    }
  }

  // ---------------------------------------------------------------------------------------- statements

  const argExpr = (s, key) => {
    const a = s.args.get(key);
    if (!a) return null;
    switch (a.kind) {
      case 'expr': return a.expr;
      case 'choice': return a.value;
      case 'text': return a.text;
      default: return null;
    }
  };

  function listInputs(state, prefix, items, slot, scope) {
    items.forEach((item, i) => setDynamicInput(state, prefix + i, input(item, slot, scope)));
  }

  function statement(s, scope) {
    const id = newId('b');
    switch (s.stmt) {
      case 'action': {
        const name = s.type.name;
        if (name === 'goto') {
          const t = s.args.get('target').target;
          const spec = SPEC_BY_TYPE['alto_goto_' + t.type];
          return fillArgs({ type: spec.type, id }, spec, (k) => t[k] ?? null, scope);
        }
        if (name === 'farm') {
          const range = argExpr(s, 'range');
          const crops = s.args.get('crops');
          const items = crops ? crops.items : [];
          const state = { type: 'alto_farm', id, extraState: { crops: items.length }, fields: { RANGE_MODE: range ? 'SET' : 'DEFAULT' } };
          if (range) setDynamicInput(state, 'RANGE', input(range, { check: 'Number', shadow: 'alto_number', default: 16 }, scope));
          listInputs(state, 'CROP', items, { check: 'String', shadow: 'alto_id', default: 'minecraft:wheat' }, scope);
          if (crops && items.length === 0) notes.push({ path: s.path + '.crops', message: 'an empty crops list means "every crop" in the editor (leave the field out)' });
          return state;
        }
        if (name === 'deposit') {
          const except = s.args.get('except');
          const only = s.args.get('only');
          const state = { type: 'alto_deposit', id, extraState: { except: except ? except.items.length : 0, only: only ? only.items.length : 0 } };
          if (except) listInputs(state, 'EXCEPT', except.items, { check: 'String', shadow: 'alto_item', default: 'minecraft:bread' }, scope);
          if (only) listInputs(state, 'ONLY', only.items, { check: 'String', shadow: 'alto_item', default: 'minecraft:cobblestone' }, scope);
          for (const [key, list] of [['except', except], ['only', only]]) {
            if (list && list.items.length === 0) notes.push({ path: `${s.path}.${key}`, message: `an empty ${key} list was left out` });
          }
          return state;
        }
        const spec = LOOKUP.blocks[name];
        return fillArgs({ type: spec.type, id }, spec, (k) => argExpr(s, k), scope);
      }
      case 'repeat':
        return fillArgs({ type: 'alto_repeat', id }, SPEC_BY_TYPE.alto_repeat, (k) => s[k], scope);
      case 'repeat_until':
        return fillArgs({ type: 'alto_repeat_until', id }, SPEC_BY_TYPE.alto_repeat_until, (k) => s[k], scope);
      case 'forever':
        return fillArgs({ type: 'alto_forever', id }, SPEC_BY_TYPE.alto_forever, (k) => s[k], scope);
      case 'if': {
        const type = s.otherwise.length ? 'alto_if_else' : 'alto_if';
        return fillArgs({ type, id }, SPEC_BY_TYPE[type], (k) => (k === 'else' ? s.otherwise : s[k]), scope);
      }
      case 'wait_until': {
        const type = s.timeout ? 'alto_wait_until_timeout' : 'alto_wait_until';
        return fillArgs({ type, id }, SPEC_BY_TYPE[type], (k) => (k === 'timeout_seconds' ? s.timeout : s[k]), scope);
      }
      case 'break':
        return { type: 'alto_break', id };
      case 'call': {
        const decl = script.procedures.find((p) => p.name === s.procedure);
        const params = [];
        for (const p of decl ? decl.params : []) {
          if (!params.some((x) => x.name === p.name)) params.push({ name: p.name, type: p.type });
        }
        for (const [k, v] of s.args) {
          if (!params.some((x) => x.name === k)) {
            params.push({ name: k, type: v.kind === 'literal' ? v.value.type : 'unknown' });
          }
        }
        const state = { type: 'alto_call', id, extraState: { name: s.procedure, params } };
        for (const p of params) {
          const v = s.args.get(p.name);
          if (!v) {
            setDynamicInput(state, 'ARG_' + p.name, null);
            continue;
          }
          const shadow = paramShadow(p.type);
          setDynamicInput(state, 'ARG_' + p.name, input(v, { check: PARAM_CHECK[p.type] ?? null, shadow: shadow ? shadow.type : null, default: shadow ? shadow.fields.VALUE : null }, scope));
        }
        return state;
      }
      case 'set_var': {
        const want = s.value.kind === 'literal' ? CHECK_OF_TYPE[s.value.value.type] : outputCheck(s.value, scope);
        const v = variableRef(s.name, s.path + '.name', want);
        const type = 'alto_set_' + VAR_BLOCK[v.check];
        const spec = SPEC_BY_TYPE[type];
        const state = { type, id, fields: { NAME: { id: v.id } } };
        setInput(state, 'VALUE', input(s.value, spec.args.value, scope));
        return state;
      }
      case 'change_var': {
        const v = variableRef(s.name, s.path + '.name', 'Number');
        if (v.check !== 'Number') {
          notes.push({ path: s.path, message: `change_var needs a number variable, but '${s.name}' is not one; the block was left out` });
          return null;
        }
        const state = { type: 'alto_change_var', id, fields: { NAME: { id: v.id } } };
        setInput(state, 'DELTA', input(s.delta, SPEC_BY_TYPE.alto_change_var.args.delta, scope));
        return state;
      }
      default:
        throw new Error('unknown statement ' + s.stmt);
    }
  }

  function chain(list, scope) {
    let first = null;
    let prev = null;
    for (const s of list) {
      const st = statement(s, scope);
      if (!st) continue;
      if (!first) first = st;
      else prev.next = { block: st };
      prev = st;
    }
    return first;
  }

  // ---------------------------------------------------------------------------------------- top level

  let y = 0;
  const place = (state, height) => {
    state.x = 0;
    state.y = y;
    y += height;
    topBlocks.push(state);
  };
  const countStatements = (first) => {
    let n = 0;
    const walk = (st) => {
      for (let b = st; b; b = b.next && b.next.block) {
        n++;
        for (const inp of Object.values(b.inputs || {})) if (inp.block && !inp.block.output) walk(inp.block);
      }
    };
    walk(first);
    return n;
  };

  for (const p of script.procedures) {
    const scope = { procedure: p };
    const params = [];
    for (const x of p.params) params.push({ name: x.name, type: x.type });
    const state = { type: 'alto_procedure_def', id: newId('b'), extraState: { name: p.name, params } };
    const first = chain(p.body, scope);
    if (first) state.next = { block: first };
    place(state, 120 + 60 * countStatements(first));
  }
  for (const h of script.handlers) {
    const ev = h.event;
    const spec = LOOKUP.events[ev.type];
    const state = fillArgs({ type: spec.type, id: newId('b') }, spec, (k) => ev[k] ?? null, { procedure: null });
    const first = chain(h.body, { procedure: null });
    if (first) state.next = { block: first };
    place(state, 120 + 60 * countStatements(first));
  }
  detached.forEach((d, i) => {
    d.x = 700;
    d.y = i * 60;
    topBlocks.push(d);
  });

  return {
    state: { blocks: { languageVersion: 0, blocks: topBlocks }, variables },
    meta: { name: script.name, description: script.description, variables: metaVars },
    notes,
  };
}

