// Canonical JSON, mirroring baritone.alto.script.codec.AstWriter (and Gson's JsonWriter): fixed key order, whole
// numbers below 2^53 without a fraction, other numbers like Java's Double.toString, defaults written out, empty
// "else" left out, call arguments sorted by name, Gson string escaping. The compact form is what Alto hashes.

import { FORMAT } from './catalog.js';
import { isWhole, javaDoubleToString } from './javaish.js';

/** A canonical number token. */
export function numberToken(v) {
  if (isWhole(v) && Math.abs(v) < 9007199254740992) return String(Math.trunc(v) || 0);
  return javaDoubleToString(v);
}

/** A JSON string token with Gson's escaping (htmlSafe off). */
export function stringToken(s) {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 0x22) out += '\\"';
    else if (c === 0x5c) out += '\\\\';
    else if (c < 0x20) {
      switch (c) {
        case 0x08: out += '\\b'; break;
        case 0x09: out += '\\t'; break;
        case 0x0a: out += '\\n'; break;
        case 0x0c: out += '\\f'; break;
        case 0x0d: out += '\\r'; break;
        default: out += '\\u' + c.toString(16).padStart(4, '0');
      }
    } else if (c === 0x2028) out += '\\u2028';
    else if (c === 0x2029) out += '\\u2029';
    else out += s[i];
  }
  return out + '"';
}

/** Serializes a tree of Map / array / string / number / boolean like Gson (pretty: two-space indent). */
export function stringify(value, pretty) {
  const parts = [];
  const nl = (depth) => {
    if (pretty) parts.push('\n' + '  '.repeat(depth));
  };
  const write = (v, depth) => {
    if (v instanceof Map) {
      if (v.size === 0) {
        parts.push('{}');
        return;
      }
      parts.push('{');
      let first = true;
      for (const [k, x] of v) {
        if (!first) parts.push(',');
        first = false;
        nl(depth + 1);
        parts.push(stringToken(k), pretty ? ': ' : ':');
        write(x, depth + 1);
      }
      nl(depth);
      parts.push('}');
    } else if (Array.isArray(v)) {
      if (v.length === 0) {
        parts.push('[]');
        return;
      }
      parts.push('[');
      v.forEach((x, i) => {
        if (i > 0) parts.push(',');
        nl(depth + 1);
        write(x, depth + 1);
      });
      nl(depth);
      parts.push(']');
    } else if (typeof v === 'string') {
      parts.push(stringToken(v));
    } else if (typeof v === 'number') {
      parts.push(numberToken(v));
    } else if (typeof v === 'boolean') {
      parts.push(v ? 'true' : 'false');
    } else {
      parts.push('null');
    }
  };
  write(value, 0);
  return parts.join('');
}

// ------------------------------------------------------------------------------------------ AST to tree

function m(entries) {
  return new Map(entries);
}

function valueTree(value) {
  return value.value;
}

export function exprTree(e) {
  switch (e.kind) {
    case 'literal':
      return valueTree(e.value);
    case 'var':
    case 'param':
      return m([['expr', e.kind], ['name', e.name]]);
    case 'item_count':
      return m([['expr', 'item_count'], ['item', exprTree(e.item)], ['where', e.where]]);
    case 'position':
      return m([['expr', 'position'], ['axis', e.axis]]);
    case 'block_at':
      return m([['expr', 'block_at'], ['x', exprTree(e.x)], ['y', exprTree(e.y)], ['z', exprTree(e.z)]]);
    case 'random':
      return m([['expr', 'random'], ['min', exprTree(e.min)], ['max', exprTree(e.max)]]);
    case 'binary':
      return m([['expr', e.op], ['a', exprTree(e.a)], ['b', exprTree(e.b)]]);
    case 'unary':
      return m([['expr', e.op], ['a', exprTree(e.a)]]);
    case 'join':
      return m([['expr', 'join'], ['parts', e.parts.map(exprTree)]]);
    case 'sensor':
      return m([['expr', e.sensor]]);
    default:
      throw new Error('unknown expression kind ' + e.kind);
  }
}

function targetTree(t) {
  const out = m([['type', t.type]]);
  switch (t.type) {
    case 'coords':
      out.set('x', exprTree(t.x));
      out.set('y', exprTree(t.y));
      out.set('z', exprTree(t.z));
      if (t.dimension != null) out.set('dimension', t.dimension);
      break;
    case 'home':
      if (t.name != null) out.set('name', t.name);
      break;
    case 'storage_zone':
      out.set('name', t.name);
      break;
    default:
      break;
  }
  return out;
}

export function statementTree(s) {
  const block = s.stmt === 'action' ? s.type.name : s.stmt;
  const out = m([['block', block]]);
  switch (s.stmt) {
    case 'action':
      for (const f of s.type.fields) {
        const arg = s.args.get(f.name);
        if (!arg) continue;
        switch (arg.kind) {
          case 'expr': out.set(f.name, exprTree(arg.expr)); break;
          case 'choice': out.set(f.name, arg.value); break;
          case 'text': out.set(f.name, arg.text); break;
          case 'list': out.set(f.name, arg.items.map(exprTree)); break;
          case 'target': out.set(f.name, targetTree(arg.target)); break;
          default: break;
        }
      }
      break;
    case 'repeat':
      out.set('times', exprTree(s.times));
      out.set('body', s.body.map(statementTree));
      break;
    case 'repeat_until':
      out.set('condition', exprTree(s.condition));
      out.set('body', s.body.map(statementTree));
      break;
    case 'forever':
      out.set('body', s.body.map(statementTree));
      break;
    case 'if':
      out.set('condition', exprTree(s.condition));
      out.set('then', s.then.map(statementTree));
      if (s.otherwise.length > 0) out.set('else', s.otherwise.map(statementTree));
      break;
    case 'wait_until':
      out.set('condition', exprTree(s.condition));
      if (s.timeout != null) out.set('timeout_seconds', exprTree(s.timeout));
      break;
    case 'call': {
      out.set('procedure', s.procedure);
      const args = new Map();
      for (const [k, v] of s.args) args.set(k, exprTree(v));
      out.set('args', args);
      break;
    }
    case 'set_var':
      out.set('name', s.name);
      out.set('value', exprTree(s.value));
      break;
    case 'change_var':
      out.set('name', s.name);
      out.set('delta', exprTree(s.delta));
      break;
    default:
      break;
  }
  return out;
}

function handlerTree(h) {
  const ev = h.event;
  const out = m([['event', ev.type]]);
  switch (ev.type) {
    case 'on_chat':
      out.set('trigger', ev.trigger);
      break;
    case 'on_health_below':
      out.set('health', ev.health);
      break;
    case 'on_item_below':
      out.set('item', ev.item);
      out.set('count', ev.count);
      out.set('where', ev.where);
      break;
    case 'every':
      out.set('seconds', ev.seconds);
      break;
    default:
      break;
  }
  out.set('body', h.body.map(statementTree));
  return out;
}

/** The canonical tree (Map objects in canonical key order). */
export function scriptTree(script) {
  return m([
    ['format', FORMAT],
    ['version', script.version],
    ['name', script.name],
    ['description', script.description],
    ['variables', script.variables.map((v) => m([['name', v.name], ['initial', valueTree(v.initial)]]))],
    ['procedures', script.procedures.map((p) => m([
      ['name', p.name],
      ['params', p.params.map((x) => m([['name', x.name], ['type', x.type]]))],
      ['body', p.body.map(statementTree)],
    ]))],
    ['events', script.handlers.map(handlerTree)],
  ]);
}

/** ScriptCodec.toCanonicalJson. */
export function toCanonicalJson(script) {
  return stringify(scriptTree(script), false);
}

/** ScriptCodec.toPrettyJson: indented with two spaces, ending with a newline. */
export function toPrettyJson(script) {
  return stringify(scriptTree(script), true) + '\n';
}
