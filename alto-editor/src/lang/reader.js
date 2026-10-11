// Builds the script AST from a JSON tree (objects are Map instances, see strictjson.js), mirroring
// baritone.alto.script.codec.AstReader: structural problems (unknown blocks, missing or unknown fields, wrong JSON
// types, invalid choices) are collected as diagnostics and reading continues, so one pass reports as many as possible.
// The script is only usable when no error was reported.

import {
  ACTION_BY_NAME, ACTIONS, AXES, BINARY_OPS, CONTROL_BLOCKS, CURRENT_VERSION, EVENT_TYPES, EXPR_KINDS, FORMAT,
  LIMITS, PARAM_TYPES, SENSORS, TARGET_TYPES, UNARY_OPS, WHERE, diagnostic,
} from './catalog.js';
import { formatNumber, hint, compareJavaStrings } from './javaish.js';

const TOP_FIELDS = ['format', 'version', 'name', 'description', 'variables', 'procedures', 'events'];

export function join(path, key) {
  return path === '' ? key : path + '.' + key;
}

export function describe(e) {
  if (e === null || e === undefined) return 'null';
  if (Array.isArray(e)) return 'an array';
  if (e instanceof Map) return 'an object';
  if (typeof e === 'boolean') return String(e);
  if (typeof e === 'number') return 'the number ' + formatNumber(e);
  const s = String(e);
  return `the string "${s.length > 40 ? s.substring(0, 40) + '...' : s}"`;
}

function literalValue(e) {
  if (typeof e === 'boolean') return { type: 'boolean', value: e };
  if (typeof e === 'number') return { type: 'number', value: e === 0 ? 0 : e };
  if (typeof e === 'string') return { type: 'string', value: e };
  return null;
}

export class AstReader {
  constructor() {
    this.diagnostics = [];
    this.nodes = 0;
    this.tooLarge = false;
  }

  hasErrors() {
    return this.diagnostics.length > 0;
  }

  error(code, path, message) {
    this.diagnostics.push(diagnostic(code, path, message));
  }

  read(root) {
    const o = this.object(root, '', 'the script');
    if (o == null) return null;
    const format = this.string(o, '', 'format', true);
    if (format != null && format !== FORMAT) {
      this.error('unsupported_format', 'format', `this is not an Alto script: format must be "${FORMAT}", got "${format}"`);
      return null;
    }
    const version = this.number(o, '', 'version', true);
    if (version != null && version !== CURRENT_VERSION) {
      this.error('unsupported_version', 'version', `this Alto understands script version ${CURRENT_VERSION}, but the script has version ${formatNumber(version)}; update Alto or save the script again from a matching editor`);
      return null;
    }
    this.onlyFields(o, '', TOP_FIELDS, 'the script');
    const name = this.string(o, '', 'name', true);
    const description = this.string(o, '', 'description', false);
    const variables = this.list(o, '', 'variables', false, (e, p) => this.variable(e, p));
    const procedures = this.list(o, '', 'procedures', false, (e, p) => this.procedure(e, p));
    const handlers = this.list(o, '', 'events', true, (e, p) => this.handler(e, p));
    if (this.hasErrors()) return null;
    return { version: CURRENT_VERSION, name, description: description == null ? '' : description, variables, procedures, handlers };
  }

  // ---------------------------------------------------------------------------------------- declarations

  variable(e, path) {
    const o = this.object(e, path, 'a variable');
    if (o == null) return null;
    this.onlyFields(o, path, ['name', 'initial'], 'a variable');
    const name = this.string(o, path, 'name', true);
    const init = this.field(o, path, 'initial', true);
    const initial = init == null ? null : literalValue(init);
    if (init != null && initial == null) {
      this.error('wrong_json_type', join(path, 'initial'), `initial must be a number, a string or true/false, got ${describe(init)}`);
    }
    return name == null || initial == null ? null : { path, name, initial };
  }

  procedure(e, path) {
    const o = this.object(e, path, 'a procedure');
    if (o == null) return null;
    this.onlyFields(o, path, ['name', 'params', 'body'], 'a procedure');
    const name = this.string(o, path, 'name', true);
    const params = this.list(o, path, 'params', false, (x, p) => this.param(x, p));
    const body = this.body(o, path, 'body', true);
    return name == null ? null : { path, name, params, body };
  }

  param(e, path) {
    const o = this.object(e, path, 'a parameter');
    if (o == null) return null;
    this.onlyFields(o, path, ['name', 'type'], 'a parameter');
    const name = this.string(o, path, 'name', true);
    const type = this.choice(o, path, 'type', PARAM_TYPES, null);
    return name == null || type == null ? null : { path, name, type };
  }

  handler(e, path) {
    const o = this.object(e, path, 'an event handler');
    if (o == null) return null;
    const kind = this.string(o, path, 'event', true);
    if (kind == null) return null;
    if (!EVENT_TYPES.includes(kind)) {
      this.error('unknown_event', path, `unknown event '${kind}'${hint(kind, EVENT_TYPES)}`);
      return null;
    }
    const what = 'event ' + kind;
    let event = null;
    switch (kind) {
      case 'on_start':
        this.onlyFields(o, path, ['event', 'body'], what);
        event = { type: kind };
        break;
      case 'on_chat': {
        this.onlyFields(o, path, ['event', 'trigger', 'body'], what);
        const trigger = this.string(o, path, 'trigger', true);
        event = trigger == null ? null : { type: kind, trigger };
        break;
      }
      case 'on_inventory_full':
        this.onlyFields(o, path, ['event', 'body'], what);
        event = { type: kind };
        break;
      case 'on_health_below': {
        this.onlyFields(o, path, ['event', 'health', 'body'], what);
        const health = this.number(o, path, 'health', true);
        event = health == null ? null : { type: kind, health };
        break;
      }
      case 'on_item_below': {
        this.onlyFields(o, path, ['event', 'item', 'count', 'where', 'body'], what);
        const item = this.string(o, path, 'item', true);
        const count = this.number(o, path, 'count', true);
        const where = this.choice(o, path, 'where', WHERE, 'inventory');
        event = item == null || count == null ? null : { type: kind, item, count, where };
        break;
      }
      case 'every': {
        this.onlyFields(o, path, ['event', 'seconds', 'body'], what);
        const seconds = this.number(o, path, 'seconds', true);
        event = seconds == null ? null : { type: kind, seconds };
        break;
      }
      default:
        break;
    }
    const body = this.body(o, path, 'body', true);
    return event == null ? null : { path, event, body };
  }

  // ---------------------------------------------------------------------------------------- statements

  body(o, path, key, required) {
    return this.list(o, path, key, required, (e, p) => this.statement(e, p));
  }

  statement(e, path) {
    const o = this.object(e, path, 'a block');
    if (o == null || !this.countNode(path)) return null;
    const block = this.string(o, path, 'block', true);
    if (block == null) return null;
    const action = ACTION_BY_NAME[block];
    if (action) return this.action(o, path, action);
    const what = 'block ' + block;
    switch (block) {
      case 'repeat': {
        this.onlyFields(o, path, ['block', 'times', 'body'], what);
        const times = this.exprField(o, path, 'times', true);
        const body = this.body(o, path, 'body', true);
        return times == null ? null : { stmt: 'repeat', path, times, body };
      }
      case 'repeat_until': {
        this.onlyFields(o, path, ['block', 'condition', 'body'], what);
        const condition = this.exprField(o, path, 'condition', true);
        const body = this.body(o, path, 'body', true);
        return condition == null ? null : { stmt: 'repeat_until', path, condition, body };
      }
      case 'forever':
        this.onlyFields(o, path, ['block', 'body'], what);
        return { stmt: 'forever', path, body: this.body(o, path, 'body', true) };
      case 'if': {
        this.onlyFields(o, path, ['block', 'condition', 'then', 'else'], what);
        const condition = this.exprField(o, path, 'condition', true);
        const then = this.body(o, path, 'then', true);
        const otherwise = this.body(o, path, 'else', false);
        return condition == null ? null : { stmt: 'if', path, condition, then, otherwise };
      }
      case 'wait_until': {
        this.onlyFields(o, path, ['block', 'condition', 'timeout_seconds'], what);
        const condition = this.exprField(o, path, 'condition', true);
        const timeout = this.exprField(o, path, 'timeout_seconds', false);
        return condition == null ? null : { stmt: 'wait_until', path, condition, timeout };
      }
      case 'break':
        this.onlyFields(o, path, ['block'], what);
        return { stmt: 'break', path };
      case 'call': {
        this.onlyFields(o, path, ['block', 'procedure', 'args'], what);
        const procedure = this.string(o, path, 'procedure', true);
        const args = this.callArgs(o, path);
        return procedure == null || args == null ? null : { stmt: 'call', path, procedure, args };
      }
      case 'set_var': {
        this.onlyFields(o, path, ['block', 'name', 'value'], what);
        const name = this.string(o, path, 'name', true);
        const value = this.exprField(o, path, 'value', true);
        return name == null || value == null ? null : { stmt: 'set_var', path, name, value };
      }
      case 'change_var': {
        this.onlyFields(o, path, ['block', 'name', 'delta'], what);
        const name = this.string(o, path, 'name', true);
        const delta = this.exprField(o, path, 'delta', true);
        return name == null || delta == null ? null : { stmt: 'change_var', path, name, delta };
      }
      default: {
        const known = [...ACTIONS.map((a) => a.name), ...CONTROL_BLOCKS];
        this.error('unknown_block', path, `unknown block '${block}'${hint(block, known)}`);
        return null;
      }
    }
  }

  /** Call arguments, sorted by name like Java's TreeMap. */
  callArgs(o, path) {
    const e = this.field(o, path, 'args', false);
    if (e == null) return new Map();
    const argsPath = join(path, 'args');
    const args = this.object(e, argsPath, 'args');
    if (args == null) return null;
    const out = new Map();
    let ok = true;
    for (const [key, value] of args) {
      const x = this.expr(value, join(argsPath, key));
      if (x == null) ok = false;
      else out.set(key, x);
    }
    if (!ok) return null;
    return new Map([...out.entries()].sort((a, b) => compareJavaStrings(a[0], b[0])));
  }

  action(o, path, type) {
    const allowed = ['block', ...type.fields.map((f) => f.name)];
    this.onlyFields(o, path, allowed, 'block ' + type.name);
    const args = new Map();
    let ok = true;
    for (const f of type.fields) {
      const e = this.field(o, path, f.name, f.required);
      if (e == null) {
        ok = ok && !f.required;
        continue;
      }
      const arg = this.actionArg(f, e, join(path, f.name));
      if (arg == null) ok = false;
      else args.set(f.name, arg);
    }
    return ok ? { stmt: 'action', path, type, args } : null;
  }

  actionArg(f, e, path) {
    switch (f.kind) {
      case 'TEXT': {
        const s = this.asString(e, path, f.name);
        return s == null ? null : { kind: 'text', text: s };
      }
      case 'CHOICE': {
        const s = this.asString(e, path, f.name);
        if (s == null) return null;
        if (!f.choices.includes(s)) {
          this.error('invalid_choice', path, `${f.name} must be one of ${f.choices.join(', ')}, got '${s}'${hint(s, f.choices)}`);
          return null;
        }
        return { kind: 'choice', value: s };
      }
      case 'ID_LIST': {
        if (!Array.isArray(e)) {
          this.error('wrong_json_type', path, `${f.name} must be an array of item ids, got ${describe(e)}`);
          return null;
        }
        const items = [];
        let ok = true;
        e.forEach((item, i) => {
          const x = this.expr(item, `${path}[${i}]`);
          if (x == null) ok = false;
          else items.push(x);
        });
        return ok ? { kind: 'list', items } : null;
      }
      case 'TARGET': {
        const target = this.target(e, path);
        return target == null ? null : { kind: 'target', target };
      }
      default: {
        const x = this.expr(e, path);
        return x == null ? null : { kind: 'expr', expr: x };
      }
    }
  }

  target(e, path) {
    const o = this.object(e, path, 'a goto target');
    if (o == null) return null;
    const type = this.string(o, path, 'type', true);
    if (type == null) return null;
    switch (type) {
      case 'coords': {
        this.onlyFields(o, path, ['type', 'x', 'y', 'z', 'dimension'], 'a coords target');
        const x = this.exprField(o, path, 'x', true);
        const y = this.exprField(o, path, 'y', true);
        const z = this.exprField(o, path, 'z', true);
        const dimension = this.string(o, path, 'dimension', false);
        return x == null || y == null || z == null ? null : { type, x, y, z, dimension };
      }
      case 'home':
        this.onlyFields(o, path, ['type', 'name'], 'a home target');
        return { type, name: this.string(o, path, 'name', false) };
      case 'storage_zone': {
        this.onlyFields(o, path, ['type', 'name'], 'a storage_zone target');
        const name = this.string(o, path, 'name', true);
        return name == null ? null : { type, name };
      }
      case 'portal':
        this.onlyFields(o, path, ['type'], 'a portal target');
        return { type };
      default:
        this.error('invalid_choice', join(path, 'type'), `target type must be one of ${TARGET_TYPES.join(', ')}, got '${type}'${hint(type, TARGET_TYPES)}`);
        return null;
    }
  }

  // ---------------------------------------------------------------------------------------- expressions

  exprField(o, path, key, required) {
    const e = this.field(o, path, key, required);
    return e == null ? null : this.expr(e, join(path, key));
  }

  expr(e, path) {
    if (e === null || e === undefined) {
      this.error('wrong_json_type', path, 'null is not allowed here');
      return null;
    }
    if (Array.isArray(e)) {
      this.error('wrong_json_type', path, 'expected a number, a string, true/false or an {"expr": ...} object, got an array');
      return null;
    }
    if (!this.countNode(path)) return null;
    if (!(e instanceof Map)) return { kind: 'literal', path, value: literalValue(e) };
    const o = e;
    const kind = this.string(o, path, 'expr', true);
    if (kind == null) return null;
    const what = 'expression ' + kind;
    if (Object.prototype.hasOwnProperty.call(SENSORS, kind)) {
      this.onlyFields(o, path, ['expr'], what);
      return { kind: 'sensor', path, sensor: kind };
    }
    if (Object.prototype.hasOwnProperty.call(BINARY_OPS, kind)) {
      this.onlyFields(o, path, ['expr', 'a', 'b'], what);
      const a = this.exprField(o, path, 'a', true);
      const b = this.exprField(o, path, 'b', true);
      return a == null || b == null ? null : { kind: 'binary', path, op: kind, a, b };
    }
    if (Object.prototype.hasOwnProperty.call(UNARY_OPS, kind)) {
      this.onlyFields(o, path, ['expr', 'a'], what);
      const a = this.exprField(o, path, 'a', true);
      return a == null ? null : { kind: 'unary', path, op: kind, a };
    }
    switch (kind) {
      case 'var':
      case 'param': {
        this.onlyFields(o, path, ['expr', 'name'], what);
        const name = this.string(o, path, 'name', true);
        if (name == null) return null;
        return { kind, path, name };
      }
      case 'item_count': {
        this.onlyFields(o, path, ['expr', 'item', 'where'], what);
        const item = this.exprField(o, path, 'item', true);
        const where = this.choice(o, path, 'where', WHERE, 'inventory');
        return item == null ? null : { kind, path, item, where };
      }
      case 'position': {
        this.onlyFields(o, path, ['expr', 'axis'], what);
        const axis = this.choice(o, path, 'axis', AXES, null);
        return axis == null ? null : { kind, path, axis };
      }
      case 'block_at': {
        this.onlyFields(o, path, ['expr', 'x', 'y', 'z'], what);
        const x = this.exprField(o, path, 'x', true);
        const y = this.exprField(o, path, 'y', true);
        const z = this.exprField(o, path, 'z', true);
        return x == null || y == null || z == null ? null : { kind, path, x, y, z };
      }
      case 'random': {
        this.onlyFields(o, path, ['expr', 'min', 'max'], what);
        const min = this.exprField(o, path, 'min', true);
        const max = this.exprField(o, path, 'max', true);
        return min == null || max == null ? null : { kind, path, min, max };
      }
      case 'join': {
        this.onlyFields(o, path, ['expr', 'parts'], what);
        const before = this.diagnostics.length;
        const parts = this.list(o, path, 'parts', true, (x, p) => this.expr(x, p));
        return this.diagnostics.length > before ? null : { kind, path, parts };
      }
      default:
        this.error('unknown_expr', path, `unknown expression '${kind}'${hint(kind, EXPR_KINDS)}`);
        return null;
    }
  }

  // ---------------------------------------------------------------------------------------- JSON helpers

  countNode(path) {
    if (++this.nodes <= LIMITS.MAX_NODES) return true;
    if (!this.tooLarge) {
      this.tooLarge = true;
      this.error('too_large', path, `the script has more than ${LIMITS.MAX_NODES} blocks and values`);
    }
    return false;
  }

  object(e, path, what) {
    if (e instanceof Map) return e;
    this.error('wrong_json_type', path, `${what} must be a JSON object {...}, got ${describe(e)}`);
    return null;
  }

  onlyFields(o, path, allowed, what) {
    for (const key of o.keys()) {
      if (!allowed.includes(key)) {
        this.error('unknown_field', join(path, key), `${what} has no field '${key}'${hint(key, allowed)}`);
      }
    }
  }

  field(o, path, key, required) {
    if (!o.has(key)) {
      if (required) this.error('missing_field', join(path, key), `missing field '${key}'`);
      return null;
    }
    const e = o.get(key);
    if (e === null || e === undefined) {
      this.error('wrong_json_type', join(path, key), `${key} must not be null; leave it out instead`);
      return null;
    }
    return e;
  }

  string(o, path, key, required) {
    const e = this.field(o, path, key, required);
    return e == null ? null : this.asString(e, join(path, key), key);
  }

  asString(e, path, key) {
    if (typeof e === 'string') return e;
    this.error('wrong_json_type', path, `${key} must be a string, got ${describe(e)}`);
    return null;
  }

  number(o, path, key, required) {
    const e = this.field(o, path, key, required);
    if (e == null) return null;
    if (typeof e === 'number') return e;
    this.error('wrong_json_type', join(path, key), `${key} must be a number, got ${describe(e)}`);
    return null;
  }

  choice(o, path, key, values, fallback) {
    const s = this.string(o, path, key, fallback == null);
    if (s == null) return fallback;
    if (!values.includes(s)) {
      this.error('invalid_choice', join(path, key), `${key} must be one of ${values.join(', ')}, got '${s}'${hint(s, values)}`);
      return fallback;
    }
    return s;
  }

  list(o, path, key, required, reader) {
    const e = this.field(o, path, key, required);
    if (e == null) return [];
    const listPath = join(path, key);
    if (!Array.isArray(e)) {
      this.error('wrong_json_type', listPath, `${key} must be an array [...], got ${describe(e)}`);
      return [];
    }
    const out = [];
    e.forEach((item, i) => {
      const itemPath = `${listPath}[${i}]`;
      if (item === null) {
        this.error('wrong_json_type', itemPath, 'null is not allowed here');
        return;
      }
      const value = reader(item, itemPath);
      if (value != null) out.push(value);
    });
    return out;
  }
}
