// (d) The built page is one self-contained file: no external scripts, styles, fonts or images, a Content
// Security Policy that blocks network access, a reasonable size, and the committed dist/ file is up to date.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { buildHtml } from '../build.mjs';
import { editorRoot, examples, outputDir } from './helpers.mjs';

const html = await buildHtml();
fs.mkdirSync(outputDir, { recursive: true });
fs.writeFileSync(path.join(outputDir, 'alto-script-editor.html'), html);

test('no http(s) script, link, style or media references', () => {
  const tagRef = /<(script|link|img|iframe|frame|source|audio|video|embed|object|track|image|use)\b[^>]*?\b(src|href|data|xlink:href)\s*=\s*["']?\s*(https?:)?\/\//gi;
  assert.deepEqual(html.match(tagRef) || [], []);
  assert.doesNotMatch(html, /<script\b[^>]*\bsrc\s*=/i, 'all scripts are inline');
  assert.doesNotMatch(html, /<link\b/i, 'no stylesheets, icons or preloads are linked');
  assert.doesNotMatch(html, /@import/i, 'no CSS imports');
  assert.doesNotMatch(html, /url\(\s*["']?\s*(https?:)?\/\//i, 'no CSS urls to the network');
  assert.doesNotMatch(html, /<base\b/i);
  assert.equal((html.match(/<script\b/gi) || []).length, 1, 'one inline script');
});

test('the Content Security Policy blocks every network request', () => {
  const m = /<meta http-equiv="Content-Security-Policy" content="([^"]+)">/.exec(html);
  assert.ok(m, 'CSP meta tag');
  const csp = m[1];
  for (const directive of ["default-src 'none'", "connect-src 'none'", "font-src 'none'", "img-src data: blob:", "object-src 'none'", "form-action 'none'"]) {
    assert.ok(csp.includes(directive), directive);
  }
  assert.doesNotMatch(csp, /https?:|\*/, 'no remote sources allowed');
  assert.ok(html.indexOf('Content-Security-Policy') < html.indexOf('<script'), 'the policy comes before the script');
});

test('the page has a reasonable size', () => {
  const kib = Buffer.byteLength(html) / 1024;
  assert.ok(kib > 300 && kib < 2500, `${kib.toFixed(0)} KiB`);
});

test('the examples, the Blockly license and the safety notes are in the page', () => {
  for (const { text } of examples()) {
    const name = JSON.parse(text).name;
    assert.ok(html.includes(JSON.stringify(name).slice(1, -1)), name);
  }
  assert.ok(html.includes('Apache License'), 'Apache-2.0 text');
  assert.ok(html.includes('TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION'));
  assert.ok(html.includes('Scripts cannot run code, read files or use the network'));
  assert.ok(html.includes('send chat as me') && html.includes('run Baritone command'));
});

test('the inline script cannot end early', () => {
  const start = html.indexOf('<script>') + '<script>'.length;
  const end = html.lastIndexOf('</script>');
  const js = html.slice(start, end);
  assert.doesNotMatch(js, /<\/script/i);
  assert.doesNotMatch(js, /<!--/);
});

test('the committed dist/alto-script-editor.html matches a fresh build (run "npm run build" after changes)', () => {
  const dist = path.join(editorRoot, 'dist', 'alto-script-editor.html');
  assert.ok(fs.existsSync(dist), 'dist/alto-script-editor.html exists');
  assert.ok(fs.readFileSync(dist, 'utf8') === html, 'dist is up to date');
});
