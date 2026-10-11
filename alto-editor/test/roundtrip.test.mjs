// (b) JSON -> workspace -> JSON round trips (headless Blockly in Node). The five examples must come back
// semantically equal (identical canonical JSON, so also the same hash). The results are written to
// test-output/*.alto.json for the Java side.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { Blockly, examples, outputDir, roundTrip } from './helpers.mjs';
import { loadScript, parseScriptText } from '../src/lang/index.js';
import { CASES, EVERYTHING } from './fixtures/cases.mjs';
import { SPECS } from '../src/blocks/specs.js';
import { paletteTypes, searchFlyout, staticCategories, toolboxDefinition, variablesFlyout, proceduresFlyout } from '../src/blocks/toolbox.js';
import { scriptToWorkspaceState } from '../src/convert/fromScript.js';
import { workspaceToScript, blockIdForPath } from '../src/convert/toScript.js';

fs.mkdirSync(outputDir, { recursive: true });

test('there are five examples', () => {
  assert.equal(examples().length, 5);
});

for (const { file, text } of examples()) {
  test(`round trip: ${file}`, () => {
    const original = loadScript(text);
    assert.ok(original.runnable, 'the example itself is valid');
    const { report, notes, editorNotes } = roundTrip(text);
    assert.deepEqual(notes, [], 'no import notes');
    assert.deepEqual(editorNotes, [], 'no loose blocks');
    assert.ok(report.script, JSON.stringify(report.diagnostics));
    assert.equal(report.canonical, original.canonical, 'semantically equal (canonical JSON)');
    assert.equal(report.hash, original.hash);
    assert.deepEqual(JSON.parse(report.canonical), JSON.parse(original.canonical));
    assert.deepEqual(report.diagnostics, original.diagnostics);
    fs.writeFileSync(path.join(outputDir, file), report.pretty);
  });
}

test('round trip: the Java EVERYTHING fixture (every block, expression, event and target)', () => {
  const original = loadScript(EVERYTHING);
  const { report, notes } = roundTrip(EVERYTHING);
  assert.deepEqual(notes, []);
  assert.equal(report.canonical, original.canonical);
  fs.writeFileSync(path.join(outputDir, 'everything.alto.json'), report.pretty);
});

test('round trip: every structurally valid validator case is unchanged unless an import note explains it', () => {
  let exact = 0;
  for (const c of CASES) {
    const parsed = parseScriptText(c.json);
    if (!parsed.script) continue;
    const { report, notes } = roundTrip(c.json);
    if (notes.length) continue;
    assert.equal(report.canonical, loadScript(c.json).canonical, c.name);
    exact++;
  }
  assert.ok(exact > 90, `${exact} exact round trips`);
});

test('a value that does not fit its slot is kept next to the script and reported', () => {
  const text = CASES.find((c) => c.name === 'trigger type_mismatch').json;
  const parsed = parseScriptText(text);
  const { state, notes } = scriptToWorkspaceState(parsed.script);
  assert.equal(notes.length, 1);
  assert.equal(notes[0].path, 'events[0].body[0].count');
  assert.equal(state.blocks.blocks.length, 2, 'the hat and the loose text block');
  const { report } = roundTrip(text);
  assert.equal(report.script, null, 'the slot stays empty (no default value is invented)');
  assert.ok(report.diagnostics.some((d) => d.code === 'missing_field' && d.path === 'events[0].body[0].count'));
});

test('every block type can be created from the palette and exported', () => {
  const types = paletteTypes();
  for (const spec of SPECS) {
    if (spec.type === 'alto_id' || spec.type === 'alto_procedure_def') continue; // shadows / made by dialog
    assert.ok(types.has(spec.type), `${spec.type} is in the palette`);
  }
  const ws = new Blockly.Workspace();
  const items = Object.values(staticCategories()).flat().filter((i) => i.kind === 'block');
  for (const item of items) {
    const block = Blockly.serialization.blocks.append(JSON.parse(JSON.stringify(item)), ws);
    assert.ok(block, item.type);
  }
  assert.ok(toolboxDefinition().contents.length >= 12);
  ws.dispose();
});

test('the dynamic flyouts list variables and custom blocks', () => {
  const vars = [{ id: 'v1', name: 'count', type: 'Number' }, { id: 'v2', name: 'label', type: 'String' }];
  const flyout = variablesFlyout(vars).filter((i) => i.kind === 'block').map((i) => i.type);
  assert.deepEqual(flyout, ['alto_var_number', 'alto_var_string', 'alto_set_number', 'alto_change_var', 'alto_set_string']);
  const procs = proceduresFlyout([{ name: 'restock', params: [{ name: 'item', type: 'item' }] }]).filter((i) => i.kind === 'block');
  assert.deepEqual(procs.map((i) => i.type), ['alto_call', 'alto_param']);
});

test('palette search finds blocks by label and name', () => {
  const types = (q) => searchFlyout(q, { variables: [], procedures: [] }).filter((i) => i.kind === 'block').map((i) => i.type);
  assert.equal(types('craft')[0], 'alto_craft');
  assert.ok(types('wait').slice(0, 3).every((t) => t.startsWith('alto_wait')));
  assert.ok(types('chat').includes('alto_send_chat') && types('chat').includes('alto_on_chat'));
  assert.ok(types('repeat until').includes('alto_repeat_until'));
  assert.deepEqual(types('zzzz'), []);
  const withProc = searchFlyout('restock', { variables: [], procedures: [{ name: 'restock', params: [] }] }).filter((i) => i.kind === 'block');
  assert.equal(withProc[0].type, 'alto_call');
});

test('diagnostic paths map to blocks', () => {
  const text = CASES.find((c) => c.name === 'inner loop without wait').json;
  const parsed = parseScriptText(text);
  const { state, meta } = scriptToWorkspaceState(parsed.script);
  const ws = new Blockly.Workspace();
  Blockly.serialization.workspaces.load(state, ws);
  const { paths } = workspaceToScript(ws, meta);
  const id = blockIdForPath(paths, 'events[0].body[0].body[1]');
  assert.equal(ws.getBlockById(id).type, 'alto_repeat');
  assert.equal(ws.getBlockById(blockIdForPath(paths, 'events[0].body[0].body[1].times')).type, 'alto_repeat', 'a literal maps to its block');
  assert.equal(ws.getBlockById(blockIdForPath(paths, 'events[0]')).type, 'alto_on_start');
  ws.dispose();
});

test('loose blocks are reported and not exported', () => {
  const ws = new Blockly.Workspace();
  Blockly.serialization.workspaces.load({ blocks: { languageVersion: 0, blocks: [
    { type: 'alto_on_start', x: 0, y: 0 },
    { type: 'alto_eat', x: 300, y: 0 },
  ] } }, ws);
  const { data, notes } = workspaceToScript(ws, { name: 'x', description: '', variables: [] });
  assert.deepEqual(data.events, [{ event: 'on_start', body: [] }]);
  assert.equal(notes.length, 1);
  assert.equal(notes[0].code, 'detached_block');
  ws.dispose();
});
