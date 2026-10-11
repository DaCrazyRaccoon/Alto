// Workspace -> script JSON. The result is plain data in canonical key order plus a map from JSON paths
// (events[0].body[2].count) to block ids, so every diagnostic can be shown on its block.

import { CURRENT_VERSION, FORMAT, PARAM_TYPES } from '../lang/catalog.js';
import { SPEC_BY_TYPE, blocklyName } from '../blocks/specs.js';

const VAR_TYPE_DEFAULTS = { Number: 0, String: '', Boolean: false };
const JSON_TYPE_OF = { Number: 'number', String: 'string', Boolean: 'boolean' };

export function isEventBlock(block) {
  const spec = SPEC_BY_TYPE[block.type];
  return Boolean(spec && spec.json.event);
}

/** Top blocks of one kind, ordered top to bottom, then left to right. */
export function orderedTopBlocks(workspace, filter) {
  return workspace.getTopBlocks(false)
    .filter((b) => !b.isInsertionMarker() && filter(b))
    .map((b) => ({ b, xy: b.getRelativeToSurfaceXY() }))
    .sort((p, q) => p.xy.y - q.xy.y || p.xy.x - q.xy.x)
    .map((p) => p.b);
}

/** The initial value stored for a variable, of the variable's type. */
export function initialOf(meta, variable) {
  const type = variable.getType() || 'Number';
  const entry = (meta.variables || []).find((v) => v.id === variable.getId());
  const value = entry ? entry.initial : undefined;
  return typeof value === JSON_TYPE_OF[type] ? value : VAR_TYPE_DEFAULTS[type] ?? 0;
}

/** Workspace variables in declaration order (the order kept in meta, new ones at the end). */
export function orderedVariables(workspace, meta) {
  const all = workspace.getVariableMap().getAllVariables();
  const order = (meta.variables || []).map((v) => v.id);
  const rank = (v) => {
    const i = order.indexOf(v.getId());
    return i < 0 ? order.length + all.indexOf(v) : i;
  };
  return [...all].sort((a, b) => rank(a) - rank(b));
}

/**
 * Converts the workspace. meta = {name, description, variables: [{id, initial}]}.
 * Returns {data, paths: Map<path, blockId>, notes: [{severity, code, message, blockId}]}.
 */
export function workspaceToScript(workspace, meta) {
  const paths = new Map();
  const notes = [];
  const varName = (block, field = 'NAME') => {
    const id = block.getFieldValue(field);
    const v = id ? workspace.getVariableMap().getVariableById(id) : null;
    return v ? v.getName() : '';
  };

  const literal = (block) => {
    const v = block.getFieldValue('VALUE');
    switch (block.type) {
      case 'alto_number': {
        const n = Number(v);
        return Number.isFinite(n) ? n : 0;
      }
      case 'alto_boolean':
        return v === 'TRUE';
      default:
        return v == null ? '' : String(v);
    }
  };

  const valueOf = (block, inputName, path) => {
    const child = block.getInputTargetBlock(inputName);
    return child ? expr(child, path) : undefined;
  };

  function expr(block, path) {
    if (!block.isShadow()) paths.set(path, block.id);
    const spec = SPEC_BY_TYPE[block.type];
    if (!spec) return undefined;
    if (spec.json.literal) return literal(block);
    switch (block.type) {
      case 'alto_var_number':
      case 'alto_var_string':
      case 'alto_var_boolean':
        return { expr: 'var', name: varName(block) };
      case 'alto_param':
        return { expr: 'param', name: block.paramName_ };
      case 'alto_join': {
        const parts = [];
        for (let i = 0; i < block.parts_; i++) {
          const v = valueOf(block, 'PART' + i, `${path}.parts[${parts.length}]`);
          if (v !== undefined) parts.push(v);
        }
        return { expr: 'join', parts };
      }
      default:
        break;
    }
    const out = { expr: spec.json.expr };
    for (const [name, arg] of Object.entries(spec.args)) {
      const key = arg.json || name;
      if (arg.kind === 'value') {
        const v = valueOf(block, blocklyName(name), `${path}.${key}`);
        if (v !== undefined) out[key] = v;
      } else if (arg.kind === 'dropdown') {
        out[key] = block.getFieldValue(blocklyName(name));
      }
    }
    return out;
  }

  function list(block, prefix, count, path) {
    const items = [];
    for (let i = 0; i < count; i++) {
      const v = valueOf(block, prefix + i, `${path}[${items.length}]`);
      if (v !== undefined) items.push(v);
    }
    return items;
  }

  function chain(first, path) {
    const out = [];
    for (let b = first; b; b = b.getNextBlock()) {
      if (b.isInsertionMarker()) continue;
      const s = statement(b, `${path}[${out.length}]`);
      if (s) out.push(s);
    }
    return out;
  }

  function fields(block, spec, path, out) {
    for (const [name, arg] of Object.entries(spec.args)) {
      const key = arg.json || name;
      const bn = blocklyName(name);
      switch (arg.kind) {
        case 'value': {
          const v = valueOf(block, bn, `${path}.${key}`);
          if (v !== undefined) out[key] = v;
          break;
        }
        case 'dropdown': {
          const v = block.getFieldValue(bn);
          if (!(arg.omitValue !== undefined && v === arg.omitValue)) out[key] = v;
          break;
        }
        case 'text':
        case 'id': {
          const v = String(block.getFieldValue(bn) ?? '');
          if (!((arg.omitIfEmpty || arg.allowEmpty) && v === '')) out[key] = v;
          break;
        }
        case 'number': {
          const n = Number(block.getFieldValue(bn));
          out[key] = Number.isFinite(n) ? n : 0;
          break;
        }
        case 'statements': {
          const body = chain(block.getInputTargetBlock(bn), `${path}.${key}`);
          if (!(key === 'else' && body.length === 0)) out[key] = body;
          break;
        }
        default:
          break;
      }
    }
    return out;
  }

  function statement(block, path) {
    paths.set(path, block.id);
    const spec = SPEC_BY_TYPE[block.type];
    if (!spec || !(spec.shape === 'statement' || spec.shape === 'c')) {
      notes.push({ severity: 'warning', code: 'editor_unknown_block', message: 'This block cannot be part of a script here.', blockId: block.id });
      return null;
    }
    const j = spec.json;
    if (j.target) {
      const target = fields(block, spec, `${path}.target`, { type: j.target });
      return { block: 'goto', target };
    }
    switch (block.type) {
      case 'alto_farm': {
        const out = { block: 'farm' };
        if (block.getFieldValue('RANGE_MODE') === 'SET') {
          const v = valueOf(block, 'RANGE', `${path}.range`);
          if (v !== undefined) out.range = v;
        }
        if (block.crops_ > 0) out.crops = list(block, 'CROP', block.crops_, `${path}.crops`);
        return out;
      }
      case 'alto_deposit': {
        const out = { block: 'deposit' };
        if (block.except_ > 0) out.except = list(block, 'EXCEPT', block.except_, `${path}.except`);
        if (block.only_ > 0) out.only = list(block, 'ONLY', block.only_, `${path}.only`);
        return out;
      }
      case 'alto_call': {
        const args = {};
        const entries = [];
        for (const p of block.params_) {
          if (entries.some((e) => e[0] === p.name)) continue;
          const v = valueOf(block, 'ARG_' + p.name, `${path}.args.${p.name}`);
          if (v !== undefined) entries.push([p.name, v]);
        }
        entries.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
        for (const [k, v] of entries) args[k] = v;
        return { block: 'call', procedure: block.procName_, args };
      }
      case 'alto_set_number':
      case 'alto_set_string':
      case 'alto_set_boolean': {
        const out = { block: 'set_var', name: varName(block) };
        const v = valueOf(block, 'VALUE', `${path}.value`);
        if (v !== undefined) out.value = v;
        return out;
      }
      case 'alto_change_var': {
        const out = { block: 'change_var', name: varName(block) };
        const v = valueOf(block, 'DELTA', `${path}.delta`);
        if (v !== undefined) out.delta = v;
        return out;
      }
      default:
        return fields(block, spec, path, { block: j.block });
    }
  }

  // ---------------------------------------------------------------------------------------- top level

  const variables = orderedVariables(workspace, meta).map((v) => ({ name: v.getName(), initial: initialOf(meta, v) }));

  const defs = orderedTopBlocks(workspace, (b) => b.type === 'alto_procedure_def');
  const procedures = defs.map((def, i) => {
    const path = `procedures[${i}]`;
    paths.set(path, def.id);
    return {
      name: def.procName_,
      params: def.params_.map((p) => ({ name: p.name, type: PARAM_TYPES.includes(p.type) ? p.type : 'string' })),
      body: chain(def.getNextBlock(), `${path}.body`),
    };
  });

  const hats = orderedTopBlocks(workspace, isEventBlock);
  const events = hats.map((hat, i) => {
    const path = `events[${i}]`;
    paths.set(path, hat.id);
    const spec = SPEC_BY_TYPE[hat.type];
    const out = fields(hat, spec, path, { event: spec.json.event });
    out.body = chain(hat.getNextBlock(), `${path}.body`);
    return out;
  });

  for (const b of workspace.getTopBlocks(false)) {
    if (b.isInsertionMarker() || b.type === 'alto_procedure_def' || isEventBlock(b)) continue;
    notes.push({
      severity: 'warning',
      code: 'detached_block',
      message: 'Not attached to an event or a custom block definition, so it is not part of the script. Snap it under a "when ..." block, or delete it.',
      blockId: b.id,
    });
  }

  const data = {
    format: FORMAT,
    version: CURRENT_VERSION,
    name: meta.name ?? '',
    description: meta.description ?? '',
    variables,
    procedures,
    events,
  };
  return { data, paths, notes };
}

/** The block for a diagnostic path: the longest path prefix that belongs to a block. */
export function blockIdForPath(paths, path) {
  let p = path;
  while (p) {
    if (paths.has(p)) return paths.get(p);
    const cut = Math.max(p.lastIndexOf('.'), p.lastIndexOf('['));
    if (cut <= 0) break;
    p = p.substring(0, cut);
  }
  return paths.get(p) ?? null;
}
