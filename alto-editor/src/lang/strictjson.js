// Strict JSON reading like baritone.alto.script.codec.StrictJson: no comments or other lenient syntax, no duplicate
// keys, finite numbers only, nesting at most 100 levels. Objects become Map instances (they keep the key order of
// the file, also for keys like "1"), arrays stay arrays, null stays null.

import { LIMITS } from './catalog.js';

export class JsonSyntaxError extends Error {
  constructor(path, message) {
    super(message);
    this.path = path;
  }
}

/** Converts plain JS data (objects, arrays, primitives) into the Map-based tree the reader works on. */
export function fromPlain(value) {
  if (Array.isArray(value)) return value.map(fromPlain);
  if (value instanceof Map) {
    const out = new Map();
    for (const [k, v] of value) out.set(k, fromPlain(v));
    return out;
  }
  if (value !== null && typeof value === 'object') {
    const out = new Map();
    for (const k of Object.keys(value)) {
      if (value[k] !== undefined) out.set(k, fromPlain(value[k]));
    }
    return out;
  }
  return value;
}

/** Converts a Map-based tree back to plain JS data (keys that look like integers may change order). */
export function toPlain(value) {
  if (Array.isArray(value)) return value.map(toPlain);
  if (value instanceof Map) {
    const out = {};
    for (const [k, v] of value) out[k] = toPlain(v);
    return out;
  }
  return value;
}

export function parseStrictJson(text) {
  let pos = 0;

  const fail = (message, path = '') => {
    let line = 1;
    let col = 1;
    for (let i = 0; i < pos && i < text.length; i++) {
      if (text.charCodeAt(i) === 10) {
        line++;
        col = 1;
      } else {
        col++;
      }
    }
    throw new JsonSyntaxError(path, `invalid JSON: ${message} at line ${line} column ${col}`);
  };

  const skipWhitespace = () => {
    while (pos < text.length) {
      const c = text.charCodeAt(pos);
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d) pos++;
      else break;
    }
  };

  const readString = () => {
    // at opening quote
    pos++;
    let out = '';
    let chunkStart = pos;
    while (true) {
      if (pos >= text.length) fail('unterminated string');
      const c = text.charCodeAt(pos);
      if (c === 0x22) {
        out += text.substring(chunkStart, pos);
        pos++;
        return out;
      }
      if (c === 0x5c) {
        out += text.substring(chunkStart, pos);
        pos++;
        if (pos >= text.length) fail('unterminated string');
        const e = text[pos];
        switch (e) {
          case '"': out += '"'; break;
          case '\\': out += '\\'; break;
          case '/': out += '/'; break;
          case 'b': out += '\b'; break;
          case 'f': out += '\f'; break;
          case 'n': out += '\n'; break;
          case 'r': out += '\r'; break;
          case 't': out += '\t'; break;
          case 'u': {
            const hex = text.substring(pos + 1, pos + 5);
            if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('invalid \\u escape');
            out += String.fromCharCode(parseInt(hex, 16));
            pos += 4;
            break;
          }
          default:
            fail(`invalid escape '\\${e}'`);
        }
        pos++;
        chunkStart = pos;
        continue;
      }
      pos++;
    }
  };

  const NUMBER_RE = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;

  const readValue = (path, depth) => {
    if (depth > LIMITS.MAX_JSON_DEPTH) {
      throw new JsonSyntaxError(path, `the JSON is nested more than ${LIMITS.MAX_JSON_DEPTH} levels deep`);
    }
    skipWhitespace();
    if (pos >= text.length) fail('unexpected end of input');
    const c = text[pos];
    if (c === '{') {
      pos++;
      const object = new Map();
      skipWhitespace();
      if (text[pos] === '}') {
        pos++;
        return object;
      }
      while (true) {
        skipWhitespace();
        if (text[pos] !== '"') fail('expected a quoted name');
        const key = readString();
        const childPath = path === '' ? key : path + '.' + key;
        skipWhitespace();
        if (text[pos] !== ':') fail("expected ':'");
        pos++;
        if (object.has(key)) throw new JsonSyntaxError(childPath, `duplicate key '${key}'`);
        object.set(key, readValue(childPath, depth + 1));
        skipWhitespace();
        if (text[pos] === ',') {
          pos++;
          continue;
        }
        if (text[pos] === '}') {
          pos++;
          return object;
        }
        fail("expected ',' or '}'");
      }
    }
    if (c === '[') {
      pos++;
      const array = [];
      skipWhitespace();
      if (text[pos] === ']') {
        pos++;
        return array;
      }
      let i = 0;
      while (true) {
        array.push(readValue(`${path}[${i++}]`, depth + 1));
        skipWhitespace();
        if (text[pos] === ',') {
          pos++;
          continue;
        }
        if (text[pos] === ']') {
          pos++;
          return array;
        }
        fail("expected ',' or ']'");
      }
    }
    if (c === '"') return readString();
    if (text.startsWith('true', pos)) {
      pos += 4;
      return true;
    }
    if (text.startsWith('false', pos)) {
      pos += 5;
      return false;
    }
    if (text.startsWith('null', pos)) {
      pos += 4;
      return null;
    }
    NUMBER_RE.lastIndex = pos;
    const m = NUMBER_RE.exec(text);
    if (m && m[0].length > 0) {
      const raw = m[0];
      pos += raw.length;
      const value = Number(raw);
      if (!Number.isFinite(value)) throw new JsonSyntaxError(path, `number out of range: ${raw}`);
      return value === 0 ? 0 : value;
    }
    fail(`unexpected character '${c}'`);
    return undefined;
  };

  const root = readValue('', 0);
  skipWhitespace();
  if (pos < text.length) {
    throw new JsonSyntaxError('', 'unexpected text after the end of the JSON document');
  }
  return root;
}
