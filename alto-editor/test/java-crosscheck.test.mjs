// (c, strongest form) When a JDK 21+ and Gson are available locally, compile Alto's own script package and run
// it on the same scripts: diagnostics (severity, code, path, message), canonical JSON, pretty JSON, hash and the
// capability sentences must be identical in JavaScript and Java. Skipped (not failed) without a JDK.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CASES, EVERYTHING } from './fixtures/cases.mjs';
import { everyBlockScript, examples, roundTrip } from './helpers.mjs';
import { loadScript, loadScriptData } from '../src/lang/index.js';
import { compareReports, findGson, findJdk, runJava } from './java/crosscheck.mjs';

const available = Boolean(findJdk() && findGson());

test('JavaScript and Java produce identical reports', { skip: available ? false : 'no JDK 21+ or Gson jar found (set ALTO_JDK / ALTO_GSON_JAR)' }, () => {
  const cases = CASES.map((c) => ({ name: c.name, json: c.json }));
  cases.push({ name: 'EVERYTHING', json: EVERYTHING });
  for (const { file, text } of examples()) {
    cases.push({ name: 'example ' + file, json: text });
    cases.push({ name: 'editor round trip of ' + file, json: roundTrip(text).report.pretty });
  }
  cases.push({ name: 'editor round trip of EVERYTHING', json: roundTrip(EVERYTHING).report.pretty });
  cases.push({ name: 'editor round trip of every palette block', json: loadScriptData(everyBlockScript().data).pretty });
  const reports = runJava(cases);
  assert.ok(reports && reports.length === cases.length);
  const failures = [];
  reports.forEach((java, i) => {
    const problems = compareReports(loadScript(cases[i].json), java);
    if (problems.length) failures.push(`${cases[i].name}:\n  ${problems.join('\n  ')}`);
  });
  assert.deepEqual(failures, []);
  // the editor's output is accepted by Java unchanged
  for (const [i, c] of cases.entries()) {
    if (c.name.startsWith('editor round trip')) assert.ok(reports[i].runnable, c.name);
  }
});
