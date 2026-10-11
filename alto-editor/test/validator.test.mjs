// (c) The JavaScript validator flags the same problems as the Java validator. The scripts and expected
// code/path pairs are copied from the Java tests (ValidatorTest, CodecTest). When a JDK 21 and Gson are available,
// the real Java loader is also run on every script and the full reports must be identical (java-crosscheck test).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CASES, EVERYTHING } from './fixtures/cases.mjs';
import { loadScript } from '../src/lang/index.js';
import { DIAGNOSTIC_CODES } from '../src/lang/catalog.js';
import { formatNumber, javaDoubleToString } from '../src/lang/javaish.js';
import { sha256Hex } from '../src/lang/sha256.js';
import { createHash } from 'node:crypto';

const show = (ds) => ds.map((d) => `${d.severity} ${d.code} at ${d.path}: ${d.message}`).join('\n');

for (const c of CASES) {
  test(`validator: ${c.name}`, () => {
    const r = loadScript(c.json);
    const ds = r.diagnostics;
    for (const [code, path] of c.expect || []) {
      const found = ds.find((d) => d.code === code && (path == null ? true : d.path === path));
      assert.ok(found, `expected ${code} at ${path}, got:\n${show(ds)}`);
      if (c.pathStartsWith) assert.ok(found.path.startsWith(c.pathStartsWith), found.path);
    }
    for (const code of c.expectCodes || []) assert.ok(ds.some((d) => d.code === code), `expected ${code}, got:\n${show(ds)}`);
    for (const code of c.absent || []) assert.ok(!ds.some((d) => d.code === code), `did not expect ${code}, got:\n${show(ds)}`);
    if (c.clean) assert.deepEqual(ds, [], show(ds));
    if (c.onlyWarnings) assert.ok(ds.every((d) => d.severity === 'warning'), show(ds));
    if (c.messageContains) assert.ok(ds.some((d) => d.message.includes(c.messageContains)), show(ds));
    if (c.codesExactly) assert.deepEqual(ds.map((d) => d.code), c.codesExactly);
    if (c.countOf) assert.equal(ds.filter((d) => d.code === c.countOf[0]).length, c.countOf[1], show(ds));
    for (const d of ds) assert.equal(d.severity, DIAGNOSTIC_CODES[d.code], `${d.code} severity`);
  });
}

test('every diagnostic code has a triggering script', () => {
  const triggered = new Set(CASES.filter((c) => c.name.startsWith('trigger ')).map((c) => c.name.slice('trigger '.length)));
  assert.deepEqual([...triggered].sort(), Object.keys(DIAGNOSTIC_CODES).sort());
});

test('the everything fixture uses every capability', () => {
  const r = loadScript(EVERYTHING);
  assert.equal(r.capabilities.capabilities.length, 15);
  assert.ok(r.capabilities.sentences.includes('Breaks blocks (build, farm, fill, mine, pickup_station).'));
  assert.ok(r.capabilities.sentences.includes('Runs Baritone commands: mine diamond_ore.'));
  assert.ok(r.capabilities.sentences.includes('Sends chat messages or server commands as you: "hello".'));
  assert.deepEqual(r.capabilities.chatTriggers, ['!go']);
});

test('numbers are shown and written like Java', () => {
  assert.equal(formatNumber(64), '64');
  assert.equal(formatNumber(-3), '-3');
  assert.equal(formatNumber(1 / 3), '0.333333333333');
  assert.equal(formatNumber(2.5), '2.5');
  assert.equal(formatNumber(1e-7), '1.0E-7');
  assert.equal(formatNumber(1e15), '1.0E15');
  assert.equal(javaDoubleToString(0.25), '0.25');
  assert.equal(javaDoubleToString(1e-5), '1.0E-5');
  assert.equal(javaDoubleToString(12345678.5), '1.23456785E7');
  assert.equal(javaDoubleToString(0.001), '0.001');
  assert.equal(javaDoubleToString(9999999.5), '9999999.5');
  assert.equal(javaDoubleToString(-2.5e-10), '-2.5E-10');
});

test('sha256 matches node:crypto', () => {
  for (const s of ['', 'abc', 'café ☃', 'x'.repeat(1000), loadScript(EVERYTHING).canonical]) {
    assert.equal(sha256Hex(s), createHash('sha256').update(s, 'utf8').digest('hex'));
  }
});
