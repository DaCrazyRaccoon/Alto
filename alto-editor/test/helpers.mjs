// Shared test helpers: headless Blockly round trips (JSON -> workspace -> JSON) and file locations.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Blockly from '../src/blockly.js';
import { defineBlocks } from '../src/blocks/define.js';
import { loadScriptData, parseScriptText } from '../src/lang/index.js';
import { scriptToWorkspaceState } from '../src/convert/fromScript.js';
import { workspaceToScript } from '../src/convert/toScript.js';
import { SPEC_BY_TYPE, blocklyName } from '../src/blocks/specs.js';
import { staticCategories } from '../src/blocks/toolbox.js';

export const editorRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const repoRoot = path.resolve(editorRoot, '..');
export const examplesDir = path.join(repoRoot, 'docs', 'alto', 'examples');
export const schemaPath = path.join(repoRoot, 'docs', 'alto', 'alto-script.schema.json');
export const outputDir = path.join(editorRoot, 'test-output');

defineBlocks();
export { Blockly };

/** [{file, text}] for docs/alto/examples/*.alto.json. */
export function examples() {
  return fs.readdirSync(examplesDir).filter((f) => f.endsWith('.alto.json')).sort()
    .map((file) => ({ file, text: fs.readFileSync(path.join(examplesDir, file), 'utf8') }));
}

/**
 * Script text -> workspace -> (Blockly save + load, like the autosave) -> script JSON.
 * Returns {report (loadScriptData of the result), data, notes (import notes), editorNotes}.
 */
export function roundTrip(text) {
  const parsed = parseScriptText(text);
  if (!parsed.script) throw new Error('not a valid script: ' + JSON.stringify(parsed.diagnostics));
  const { state, meta, notes } = scriptToWorkspaceState(parsed.script);
  const ws = new Blockly.Workspace();
  Blockly.serialization.workspaces.load(state, ws);
  const saved = JSON.parse(JSON.stringify(Blockly.serialization.workspaces.save(ws)));
  const ws2 = new Blockly.Workspace();
  Blockly.serialization.workspaces.load(saved, ws2);
  const out = workspaceToScript(ws2, JSON.parse(JSON.stringify(meta)));
  ws.dispose();
  ws2.dispose();
  return { report: loadScriptData(out.data), data: out.data, notes, editorNotes: out.notes };
}

/** Exports a workspace built from Blockly block states placed under one "when the script starts" hat. */
export function scriptFromBlockStates(statementStates, extraTopBlocks = [], variables = [], meta = {}) {
  const ws = new Blockly.Workspace();
  let first = null;
  let prev = null;
  for (const s of statementStates) {
    const copy = JSON.parse(JSON.stringify(s));
    if (!first) first = copy;
    else prev.next = { block: copy };
    prev = copy;
  }
  const hat = { type: 'alto_on_start', x: 0, y: 0 };
  if (first) hat.next = { block: first };
  Blockly.serialization.workspaces.load({
    blocks: { languageVersion: 0, blocks: [hat, ...extraTopBlocks] },
    variables: variables.map((v) => ({ name: v.name, id: v.id, type: v.type })),
  }, ws);
  const out = workspaceToScript(ws, {
    name: meta.name ?? 'generated',
    description: meta.description ?? '',
    variables: variables.map((v) => ({ id: v.id, initial: v.initial })),
  });
  ws.dispose();
  return out;
}

// ------------------------------------------------------------------------------------------ every palette block

const TRUE = { block: { type: 'alto_boolean', fields: { VALUE: 'TRUE' } } };
const WAIT = { type: 'alto_wait', inputs: { SECONDS: { shadow: { type: 'alto_number', fields: { VALUE: 1 } } } } };

/** Fills empty condition slots of a block state (and of the blocks inside it). */
function fillConditions(state) {
  const spec = SPEC_BY_TYPE[state.type];
  if (spec) {
    for (const [name, arg] of Object.entries(spec.args)) {
      const bn = blocklyName(name);
      if (arg.kind === 'value' && arg.check === 'Boolean') {
        state.inputs = state.inputs || {};
        if (!state.inputs[bn] || !state.inputs[bn].block) state.inputs[bn] = JSON.parse(JSON.stringify(TRUE));
      }
      if (arg.kind === 'statements' && ['repeat', 'repeat_until', 'forever'].includes(spec.json.block)) {
        state.inputs = state.inputs || {};
        state.inputs[bn] = { block: JSON.parse(JSON.stringify(WAIT)) };
      }
    }
  }
  for (const input of Object.values(state.inputs || {})) if (input.block) fillConditions(input.block);
  return state;
}

/** A script made of every palette block (statements chained, reporters inside messages), as the editor exports it. */
export function everyBlockScript() {
  const vars = [
    { id: 'vn', name: 'count', type: 'Number', initial: 0 },
    { id: 'vs', name: 'label', type: 'String', initial: 'x' },
    { id: 'vb', name: 'flag', type: 'Boolean', initial: true },
  ];
  const statements = [];
  const reporters = [];
  for (const items of Object.values(staticCategories())) {
    for (const item of items) {
      if (item.kind !== 'block') continue;
      const spec = SPEC_BY_TYPE[item.type];
      const state = fillConditions(JSON.parse(JSON.stringify(item)));
      if (spec.shape === 'reporter') reporters.push(state);
      else if (spec.shape === 'statement' || spec.shape === 'c') {
        if (item.type === 'alto_break' || item.type === 'alto_stop') continue;
        statements.push(state);
      }
    }
  }
  for (const r of reporters) {
    statements.push({ type: 'alto_message', inputs: { TEXT: { shadow: { type: 'alto_text', fields: { VALUE: '' } }, block: r } } });
  }
  // variables, custom blocks, break and stop
  for (const [type, id, value] of [['alto_set_number', 'vn', { type: 'alto_number', fields: { VALUE: 3 } }], ['alto_set_string', 'vs', { type: 'alto_text', fields: { VALUE: 'y' } }], ['alto_set_boolean', 'vb', { type: 'alto_boolean', fields: { VALUE: 'FALSE' } }]]) {
    statements.push({ type, fields: { NAME: { id } }, inputs: { VALUE: { shadow: value } } });
  }
  statements.push({ type: 'alto_change_var', fields: { NAME: { id: 'vn' } }, inputs: { DELTA: { shadow: { type: 'alto_number', fields: { VALUE: 1 } } } } });
  statements.push({ type: 'alto_message', inputs: { TEXT: { block: { type: 'alto_join', extraState: { parts: 3 }, inputs: {
    PART0: { block: { type: 'alto_var_number', fields: { NAME: { id: 'vn' } } } },
    PART1: { block: { type: 'alto_var_string', fields: { NAME: { id: 'vs' } } } },
    PART2: { block: { type: 'alto_var_boolean', fields: { NAME: { id: 'vb' } } } },
  } } } } });
  statements.push({ type: 'alto_call', extraState: { name: 'helper', params: [{ name: 'n', type: 'number' }, { name: 'what', type: 'item' }] },
    inputs: { ARG_n: { shadow: { type: 'alto_number', fields: { VALUE: 2 } } }, ARG_what: { shadow: { type: 'alto_item', fields: { VALUE: 'minecraft:stick' } } } } });
  statements.push({ type: 'alto_forever', inputs: { BODY: { block: { ...WAIT, next: { block: { type: 'alto_break' } } } } } });
  statements.push({ type: 'alto_stop' });
  const def = {
    type: 'alto_procedure_def', x: 900, y: 0,
    extraState: { name: 'helper', params: [{ name: 'n', type: 'number' }, { name: 'what', type: 'item' }] },
    next: { block: { type: 'alto_craft', inputs: {
      ITEM: { shadow: { type: 'alto_item', fields: { VALUE: 'minecraft:stick' } }, block: { type: 'alto_param', extraState: { name: 'what', type: 'item' } } },
      COUNT: { shadow: { type: 'alto_number', fields: { VALUE: 1 } }, block: { type: 'alto_param', extraState: { name: 'n', type: 'number' } } },
    } } },
  };
  const extraEvents = ['alto_on_chat', 'alto_on_inventory_full', 'alto_on_health_below', 'alto_on_item_below', 'alto_every']
    .map((type, i) => ({ type, x: 0, y: 4000 + i * 200, next: { block: { type: 'alto_eat' } } }));
  return scriptFromBlockStates(statements, [def, ...extraEvents], vars, { name: 'every palette block' });
}
