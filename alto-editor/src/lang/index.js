// Entry point like baritone.alto.script.ScriptLoader: parse, validate, hash and summarize a script in one call.

import { LIMITS, diagnostic } from './catalog.js';
import { AstReader } from './reader.js';
import { JsonSyntaxError, fromPlain, parseStrictJson } from './strictjson.js';
import { validate } from './validator.js';
import { capabilitySummary } from './capabilities.js';
import { toCanonicalJson, toPrettyJson } from './writer.js';
import { sha256Hex } from './sha256.js';

/** ScriptCodec.parse for text: {script, diagnostics} (script is null when there are structural errors). */
export function parseScriptText(text) {
  if (text.length > LIMITS.MAX_SCRIPT_CHARS) {
    return { script: null, diagnostics: [diagnostic('too_large', '', `the script is larger than ${LIMITS.MAX_SCRIPT_CHARS} characters`)] };
  }
  let root;
  try {
    root = parseStrictJson(text);
  } catch (e) {
    if (e instanceof JsonSyntaxError) {
      return { script: null, diagnostics: [diagnostic('json_syntax', e.path, e.message)] };
    }
    throw e;
  }
  return parseScriptTree(root);
}

/** Reads an already parsed JSON tree (Map objects) or plain JS data. */
export function parseScriptTree(root) {
  const reader = new AstReader();
  const script = reader.read(root instanceof Map ? root : fromPlain(root));
  return { script: reader.hasErrors() ? null : script, diagnostics: reader.diagnostics };
}

function report(parsed) {
  if (!parsed.script) {
    return { script: null, diagnostics: parsed.diagnostics, hash: null, capabilities: null, canonical: null, pretty: null };
  }
  const canonical = toCanonicalJson(parsed.script);
  return {
    script: parsed.script,
    diagnostics: validate(parsed.script),
    hash: sha256Hex(canonical),
    capabilities: capabilitySummary(parsed.script),
    canonical,
    pretty: toPrettyJson(parsed.script),
  };
}

/** ScriptLoader.load: everything the editor needs about a script text. runnable = parsed and no errors. */
export function loadScript(text) {
  const r = report(parseScriptText(text));
  r.runnable = r.script != null && !r.diagnostics.some((d) => d.severity === 'error');
  return r;
}

/** Same as loadScript for plain JS data (the editor's own output). */
export function loadScriptData(data) {
  const r = report(parseScriptTree(data));
  r.runnable = r.script != null && !r.diagnostics.some((d) => d.severity === 'error');
  return r;
}

export { validate } from './validator.js';
export { toCanonicalJson, toPrettyJson, stringify, scriptTree } from './writer.js';
export { capabilitySummary } from './capabilities.js';
export { sha256Hex } from './sha256.js';
export { fromPlain, toPlain, parseStrictJson } from './strictjson.js';
