// (d, run time) Opens the built page from a file:// URL in jsdom (Blockly's own Node dependency) with a resource
// loader that records every request: the page must start, list the examples, validate, and round-trip every
// example through the real UI code, without a single request. jsdom cannot lay out SVG, so text measuring is
// stubbed; everything else is the shipped page.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { JSDOM, ResourceLoader, VirtualConsole } from 'jsdom';
import { editorRoot, examples } from './helpers.mjs';
import { buildHtml } from '../build.mjs';
import { loadScript } from '../src/lang/index.js';

const html = await buildHtml();
const fileUrl = pathToFileURL(path.join(editorRoot, 'dist', 'alto-script-editor.html')).href;

async function openPage({ blockStorage = false, autosave = null, url = fileUrl } = {}) {
  const requests = [];
  const errors = [];
  class RecordingLoader extends ResourceLoader {
    fetch(url) {
      requests.push(url);
      return null;
    }
  }
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', (e) => errors.push(e.message));
  virtualConsole.on('error', (e) => errors.push(String(e)));
  // jsdom keeps storage per page, so a saved state is put in place before the editor starts
  const source = autosave == null ? html
    : html.replace('<head>', `<head><script>localStorage.setItem('alto-editor.autosave', ${JSON.stringify(autosave).replace(/</g, '\u003c')});</script>`);
  const dom = new JSDOM(source, {
    url,
    runScripts: 'dangerously',
    resources: new RecordingLoader(),
    pretendToBeVisual: true,
    virtualConsole,
    beforeParse(window) {
      window.TextEncoder = TextEncoder;
      window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
      window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
      window.SVGElement.prototype.getBBox = function getBBox() { return { x: 0, y: 0, width: 40, height: 16 }; };
      window.SVGElement.prototype.getComputedTextLength = function len() { return (this.textContent || '').length * 7; };
      window.HTMLCanvasElement.prototype.getContext = () => null;
      window.HTMLDialogElement.prototype.showModal = function showModal() { this.open = true; };
      window.HTMLDialogElement.prototype.close = function close() { this.open = false; };
      window.fetch = (url) => {
        requests.push(String(url));
        return Promise.reject(new Error('network is not allowed'));
      };
      if (blockStorage) {
        Object.defineProperty(window, 'localStorage', { get() { throw new window.DOMException('blocked', 'SecurityError'); } });
      }
    },
  });
  await new Promise((r) => setTimeout(r, 400));
  return { dom, window: dom.window, requests, errors };
}

test('the page starts from file:// without any request and validates the starter script', async () => {
  const { window, requests, errors } = await openPage();
  try {
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, []);
    const doc = window.document;
    assert.equal(doc.getElementById('problem-summary').textContent, 'Ready to run: no problems found.');
    assert.equal(doc.getElementById('examples').options.length, 6);
    assert.ok(window.altoEditor.workspace.getAllBlocks(false).length > 0);
    assert.match(doc.getElementById('json-view').textContent, /"format": "alto-script"/);
  } finally {
    window.close();
  }
});

test('every example opens in the page and its JSON view equals the canonical example', async () => {
  const { window, requests, errors } = await openPage();
  try {
    for (const { file, text } of examples()) {
      await window.altoEditor.openText(text, file);
      await new Promise((r) => setTimeout(r, 100));
      const doc = window.document;
      assert.equal(doc.getElementById('script-name').value, JSON.parse(text).name, file);
      assert.equal(doc.getElementById('problem-summary').textContent, 'Ready to run: no problems found.', file);
      assert.equal(window.altoEditor.exportJson(), loadScript(text).pretty, file);
      assert.ok(doc.getElementById('capabilities').textContent.includes('When you run it, this script'), file);
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, []);
  } finally {
    window.close();
  }
});

test('the page works when browser storage is blocked', async () => {
  const { window, requests, errors } = await openPage({ blockStorage: true });
  try {
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, []);
    assert.equal(window.document.getElementById('problem-summary').textContent, 'Ready to run: no problems found.');
    assert.match(window.document.getElementById('autosave-status').textContent, /Autosave off/);
  } finally {
    window.close();
  }
});

// jsdom has no localStorage for file:// pages (browsers do), so this one uses a local origin; nothing is loaded
const STORAGE_URL = 'http://localhost/alto-script-editor.html';

test('the workspace is restored from the autosave', async () => {
  const first = await openPage({ url: STORAGE_URL });
  const [{ file, text }] = examples();
  await first.window.altoEditor.openText(text, file);
  await new Promise((r) => setTimeout(r, 1000));
  const saved = first.window.localStorage.getItem('alto-editor.autosave');
  first.window.close();
  assert.ok(saved, 'autosaved');
  const { window, errors, requests } = await openPage({ autosave: saved, url: STORAGE_URL });
  try {
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, []);
    assert.equal(window.document.getElementById('script-name').value, JSON.parse(text).name);
    assert.equal(window.altoEditor.exportJson(), loadScript(text).pretty);
  } finally {
    window.close();
  }
});
