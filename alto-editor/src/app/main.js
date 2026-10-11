// The editor application: workspace, toolbar, live validation, files, registry, settings.

import Blockly from '../blockly.js';
import * as En from 'blockly/msg/en';
import { EXAMPLES } from 'alto:examples';
import { BLOCKLY_LICENSE, BLOCKLY_VERSION } from 'alto:about';
import { defineBlocks } from '../blocks/define.js';
import { hooks, withMutation, PARAM_LABEL } from '../blocks/dynamic.js';
import { searchFlyout, proceduresFlyout, toolboxDefinition, variablesFlyout } from '../blocks/toolbox.js';
import { getThemes } from './theme.js';
import { alertDialog, confirmDialog, el, openDialog, promptDialog } from './dialogs.js';
import { storageAvailable, storageGet, storageRemove, storageSet } from './storage.js';
import { describePath } from './describe.js';
import { blockIdForPath, orderedTopBlocks, orderedVariables, initialOf, workspaceToScript } from '../convert/toScript.js';
import { scriptToWorkspaceState } from '../convert/fromScript.js';
import { fromPlain, loadScriptData, parseScriptText, stringify } from '../lang/index.js';
import { LIMITS, PARAM_TYPES, isValidName } from '../lang/catalog.js';
import { onRegistryChange, parseRegistry, registryInfo, setRegistry } from '../registry.js';

const $ = (id) => document.getElementById(id);

const state = {
  ws: null,
  meta: { name: 'My script', description: '', variables: [] },
  fileName: null,
  dirHandle: null,
  writtenThisSession: new Set(),
  last: null, // {data, paths, report, diagnostics}
  cleanCanonical: null,
  importNotes: [],
  searchQuery: '',
  warned: new Map(), // blockId -> warning text
  loading: false,
};

// ------------------------------------------------------------------------------------------ startup

function setupBlockly() {
  Blockly.setLocale(En);
  defineBlocks();
  Blockly.dialog.setAlert((message, callback) => alertDialog('Alto Script Editor', message).then(() => callback && callback()));
  Blockly.dialog.setConfirm((message, callback) => confirmDialog('Please confirm', message).then((ok) => callback(ok)));
  Blockly.dialog.setPrompt((message, defaultValue, callback) => {
    promptDialog('Alto Script Editor', message, defaultValue, (text) => (isValidName(text.trim()) ? null
      : 'Use a letter or _ first, then letters, digits or _ (at most 64 characters).')).then((r) => callback(r == null ? null : r.trim()));
  });
  for (const id of ['blockComment', 'blockDisable', 'blockHelp']) {
    try {
      Blockly.ContextMenuRegistry.registry.unregister(id);
    } catch (e) {
      // not registered in this Blockly version
    }
  }
  for (const name of ['undo', 'redo']) {
    try {
      Blockly.ShortcutRegistry.registry.unregister(name);
    } catch (e) {
      // handled by the page instead
    }
  }
}

function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? getThemes().dark : getThemes().light;
}

function injectWorkspace() {
  const ws = Blockly.inject($('blockly'), {
    toolbox: toolboxDefinition(),
    renderer: 'zelos',
    theme: currentTheme(),
    media: 'data:,',
    sounds: false,
    trashcan: false,
    comments: false,
    disable: false,
    collapse: true,
    zoom: { controls: false, wheel: true, startScale: 0.75, maxScale: 2, minScale: 0.3, scaleSpeed: 1.15, pinch: true },
    move: { scrollbars: true, drag: true, wheel: true },
    grid: { spacing: 32, length: 2, colour: 'rgba(127,127,127,0.25)', snap: false },
    maxInstances: {},
    oneBasedIndex: true,
  });
  state.ws = ws;
  ws.registerToolboxCategoryCallback('ALTO_VARIABLES', () => variablesFlyout(variableList()));
  ws.registerToolboxCategoryCallback('ALTO_PROCEDURES', () => proceduresFlyout(procedureList()));
  ws.registerToolboxCategoryCallback('ALTO_SEARCH', () => searchFlyout(state.searchQuery, { variables: variableList(), procedures: procedureList() }));
  ws.registerButtonCallback('ALTO_CREATE_VARIABLE', () => createVariable());
  ws.registerButtonCallback('ALTO_CREATE_PROCEDURE', () => editProcedure(null));
  hooks.editProcedure = (block) => editProcedure(block);
  ws.addChangeListener(onWorkspaceChange);
  new ResizeObserver(() => Blockly.svgResize(ws)).observe($('blockly'));
  return ws;
}

function variableList() {
  return orderedVariables(state.ws, state.meta).map((v) => ({ id: v.getId(), name: v.getName(), type: v.getType() || 'Number' }));
}

function procedureList() {
  return orderedTopBlocks(state.ws, (b) => b.type === 'alto_procedure_def').map((b) => ({ name: b.procName_, params: b.params_.map((p) => ({ ...p })), block: b }));
}

function refreshToolbox() {
  const tb = state.ws.getToolbox();
  if (tb && tb.getSelectedItem && tb.getSelectedItem()) tb.refreshSelection();
}

// ------------------------------------------------------------------------------------------ change handling

let validateTimer = null;
let autosaveTimer = null;

function onWorkspaceChange(e) {
  if (state.loading) return;
  if (e.isUiEvent) {
    if (e.type === Blockly.Events.SELECTED || e.type === Blockly.Events.CLICK) return;
    return;
  }
  scheduleValidate();
  scheduleAutosave();
  updateUndoButtons();
  if (e.type === Blockly.Events.VAR_CREATE || e.type === Blockly.Events.VAR_DELETE || e.type === Blockly.Events.VAR_RENAME) {
    renderVariables();
    refreshToolbox();
  }
}

function scheduleValidate() {
  clearTimeout(validateTimer);
  validateTimer = setTimeout(validateNow, 180);
}

function scheduleAutosave() {
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(autosave, 700);
}

function updateUndoButtons() {
  if (!state.ws) return;
  $('btn-undo').disabled = state.ws.getUndoStack().length === 0;
  $('btn-redo').disabled = state.ws.getRedoStack().length === 0;
}

/** Exports the workspace, runs the Alto validator and updates every view. */
function validateNow() {
  clearTimeout(validateTimer);
  const ws = state.ws;
  const { data, paths, notes } = workspaceToScript(ws, state.meta);
  const report = loadScriptData(data);
  const diagnostics = report.diagnostics.map((d) => ({ ...d, blockId: blockIdForPath(paths, d.path) }));
  for (const n of notes) diagnostics.push({ severity: n.severity, code: n.code, path: '', message: n.message, blockId: n.blockId, editorOnly: true });
  state.last = { data, paths, report, diagnostics };
  const json = report.script ? report.pretty : stringify(fromPlain(data), true) + '\n';
  state.last.json = json;
  renderProblems();
  applyBlockWarnings(diagnostics);
  renderSummary(report);
  $('json-view').textContent = json;
  updateDirty();
}

function applyBlockWarnings(diagnostics) {
  const ws = state.ws;
  const texts = new Map();
  for (const d of diagnostics) {
    if (!d.blockId) continue;
    const line = `${d.severity === 'error' ? 'Error' : 'Warning'}: ${d.message}`;
    texts.set(d.blockId, texts.has(d.blockId) ? texts.get(d.blockId) + '\n\n' + line : line);
  }
  Blockly.Events.disable();
  try {
    for (const [id, old] of state.warned) {
      if (!texts.has(id)) {
        const b = ws.getBlockById(id);
        if (b && !b.isDeadOrDying()) b.setWarningText(null);
        state.warned.delete(id);
      } else if (texts.get(id) === old) {
        texts.delete(id);
      }
    }
    for (const [id, text] of texts) {
      const b = ws.getBlockById(id);
      if (!b || b.isShadow()) continue;
      b.setWarningText(text);
      state.warned.set(id, text);
    }
    for (const b of ws.getAllBlocks(false)) {
      const svg = b.getSvgRoot && b.getSvgRoot();
      if (!svg) continue;
      const own = diagnostics.filter((d) => d.blockId === b.id);
      svg.classList.toggle('altoHasError', own.some((d) => d.severity === 'error'));
    }
  } finally {
    Blockly.Events.enable();
  }
}

const HINTS = {
  missing_field: 'A slot of this block is empty:',
};

function renderProblems() {
  const { diagnostics, data, report } = state.last;
  const list = $('problem-list');
  list.textContent = '';
  const errors = diagnostics.filter((d) => d.severity === 'error').length;
  const warnings = diagnostics.length - errors;
  const summary = $('problem-summary');
  summary.className = 'problem-summary ' + (errors ? 'is-error' : warnings ? 'is-warning' : 'is-ok');
  if (errors) {
    summary.textContent = `${errors} error${errors === 1 ? '' : 's'}${warnings ? ` and ${warnings} warning${warnings === 1 ? '' : 's'}` : ''}: Alto will not run this script until the errors are fixed.`;
  } else if (warnings) {
    summary.textContent = `Ready to run, with ${warnings} warning${warnings === 1 ? '' : 's'} (Alto shows them when you confirm the script).`;
  } else {
    summary.textContent = 'Ready to run: no problems found.';
  }
  $('tab-problems-count').textContent = errors ? String(errors) : warnings ? String(warnings) : '';
  $('tab-problems-count').className = 'badge ' + (errors ? 'is-error' : warnings ? 'is-warning' : '');
  const sorted = [...diagnostics].sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'error' ? -1 : 1));
  for (const d of sorted) {
    const where = d.editorOnly ? 'workspace' : describePath(data, d.path);
    const item = el('li', {},
      el('button', {
        type: 'button',
        class: 'problem ' + (d.severity === 'error' ? 'is-error' : 'is-warning'),
        onclick: () => revealProblem(d),
        'aria-label': `${d.severity}: ${d.message}. Location: ${where}`,
      },
      el('span', { class: 'problem-icon', 'aria-hidden': 'true', text: d.severity === 'error' ? '✖' : '⚠' }),
      el('span', { class: 'problem-text' },
        el('span', { class: 'problem-message', text: (HINTS[d.code] ? HINTS[d.code] + ' ' : '') + capitalize(d.message) }),
        el('span', { class: 'problem-where' },
          el('span', { text: where }),
          d.path ? el('code', { text: d.path }) : null,
          el('code', { class: 'problem-code', text: d.code })))));
    list.append(item);
  }
  const notesBox = $('import-notes');
  notesBox.hidden = state.importNotes.length === 0;
  const notesList = $('import-notes-list');
  notesList.textContent = '';
  for (const n of state.importNotes) notesList.append(el('li', {}, el('code', { text: n.path }), ' ' + n.message));
  void report;
}

function capitalize(s) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

function revealProblem(d) {
  const ws = state.ws;
  if (d.blockId) {
    const block = ws.getBlockById(d.blockId);
    if (block) {
      ws.centerOnBlock(block.id, true);
      try {
        Blockly.common.setSelected(block);
      } catch (e) {
        if (block.select) block.select();
      }
      const root = block.getSvgRoot();
      root.classList.remove('altoFlash');
      void root.getBoundingClientRect();
      root.classList.add('altoFlash');
      setTimeout(() => root.classList.remove('altoFlash'), 1600);
      return;
    }
  }
  if (d.path === 'name' || d.path === 'description') {
    showTab('script');
    $(d.path === 'name' ? 'script-name' : 'script-description').focus();
    return;
  }
  const m = /^variables\[(\d+)\]/.exec(d.path || '');
  if (m) {
    showTab('script');
    const row = document.querySelectorAll('#variable-list li')[Number(m[1])];
    if (row) {
      row.scrollIntoView({ block: 'nearest' });
      const input = row.querySelector('input, select');
      if (input) input.focus();
    }
  }
}

function renderSummary(report) {
  const box = $('capabilities');
  box.textContent = '';
  if (!report.script) {
    box.append(el('p', { class: 'muted', text: 'The summary appears when the script has no structural errors (empty slots, unknown blocks).' }));
    $('hash').textContent = '';
    return;
  }
  const sentences = report.capabilities.sentences;
  if (!sentences.length) {
    box.append(el('p', { text: 'This script does nothing in the world (only messages, waits and variables).' }));
  } else {
    box.append(el('p', { text: 'When you run it, this script:' }));
    box.append(el('ul', {}, sentences.map((s) => el('li', { text: s }))));
  }
  $('hash').textContent = report.hash;
}

// ------------------------------------------------------------------------------------------ warnings on the page

function toast(message, kind = 'info') {
  const t = $('toast');
  t.textContent = message;
  t.className = 'toast show ' + kind;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => {
    t.className = 'toast';
  }, 4200);
}

// ------------------------------------------------------------------------------------------ autosave and dirty state

function autosave() {
  if (!state.ws) return;
  let payload;
  try {
    payload = JSON.stringify({
      v: 1,
      blockly: Blockly.serialization.workspaces.save(state.ws),
      meta: pruneMeta(),
      fileName: state.fileName,
      clean: state.cleanCanonical,
      savedAt: new Date().toISOString(),
    });
  } catch (e) {
    return;
  }
  const ok = storageSet('autosave', payload);
  $('autosave-status').textContent = ok ? 'Autosaved in this browser' : 'Autosave off (browser storage unavailable)';
}

function pruneMeta() {
  const ids = new Set(state.ws.getVariableMap().getAllVariables().map((v) => v.getId()));
  return { ...state.meta, variables: state.meta.variables.filter((v) => ids.has(v.id)) };
}

function restoreAutosave() {
  const text = storageGet('autosave');
  if (!text) return false;
  try {
    const saved = JSON.parse(text);
    if (!saved || saved.v !== 1) return false;
    state.loading = true;
    Blockly.serialization.workspaces.load(saved.blockly, state.ws);
    state.meta = { name: saved.meta?.name ?? 'My script', description: saved.meta?.description ?? '', variables: saved.meta?.variables ?? [] };
    state.fileName = saved.fileName || null;
    state.cleanCanonical = saved.clean ?? null;
    return true;
  } catch (e) {
    state.ws.clear();
    toast('The autosaved workspace could not be restored; starting with an empty script.', 'warn');
    return false;
  } finally {
    state.loading = false;
    state.ws.clearUndo();
  }
}

function currentCanonical() {
  return state.last ? (state.last.report.canonical || JSON.stringify(state.last.data)) : null;
}

function isDirty() {
  const hasBlocks = state.ws.getTopBlocks(false).length > 0;
  return hasBlocks && currentCanonical() !== state.cleanCanonical;
}

function updateDirty() {
  $('dirty-status').textContent = isDirty() ? (state.fileName ? 'Unsaved changes' : 'Not saved to a file yet') : '';
}

function markClean() {
  state.cleanCanonical = currentCanonical();
  updateDirty();
  scheduleAutosave();
}

// ------------------------------------------------------------------------------------------ loading scripts

const STARTER = {
  format: 'alto-script', version: 1, name: 'My script', description: '',
  events: [{ event: 'on_start', body: [{ block: 'message', text: 'Hello from Alto! Drag blocks from the palette to build your script.' }] }],
};

function fitView() {
  const ws = state.ws;
  // blocks render in the next animation frame; sizes are needed now for tidying up and scrolling
  Blockly.renderManagement.triggerQueuedRenders(ws);
  ws.cleanUp();
  Blockly.renderManagement.triggerQueuedRenders(ws);
  ws.setScale(0.8);
  const top = orderedTopBlocks(ws, () => true)[0];
  if (top) {
    const xy = top.getRelativeToSurfaceXY();
    ws.scroll(40 - xy.x * ws.scale, 30 - xy.y * ws.scale);
  }
}

function loadParsedScript(script, fileName, notes = []) {
  const { state: wsState, meta, notes: importNotes } = scriptToWorkspaceState(script);
  state.loading = true;
  try {
    state.ws.clear();
    state.meta = meta;
    Blockly.serialization.workspaces.load(wsState, state.ws);
    fitView();
  } finally {
    state.loading = false;
  }
  state.ws.clearUndo();
  state.importNotes = [...notes, ...importNotes];
  state.fileName = fileName || null;
  state.warned.clear();
  syncScriptFields();
  renderVariables();
  validateNow();
  markClean();
  updateUndoButtons();
  refreshToolbox();
}

async function openText(text, fileName) {
  let probe = null;
  try {
    probe = JSON.parse(text);
  } catch (e) {
    // reported below as a script syntax error
  }
  if (probe && probe.format === 'alto-registry') {
    loadRegistryText(text, fileName);
    return;
  }
  const parsed = parseScriptText(text);
  if (!parsed.script) {
    const items = parsed.diagnostics.slice(0, 25).map((d) => el('li', {}, el('code', { text: d.path || '(file)' }), ' ' + d.message));
    await alertDialog('This file cannot be opened as blocks', [
      `${fileName || 'The file'} is not a valid Alto script (the editor only opens files that Alto could read):`,
      el('ul', { class: 'dialog-list' }, items),
      parsed.diagnostics.length > 25 ? `…and ${parsed.diagnostics.length - 25} more.` : '',
    ].filter(Boolean));
    return;
  }
  if (isDirty() && !(await confirmDialog('Replace the current script?', 'The blocks on the workspace have not been saved to a file. Open the other script anyway? (Undo will not bring them back.)', 'Open', true))) {
    return;
  }
  loadParsedScript(parsed.script, fileName && /\.json$/i.test(fileName) ? fileName : null);
  toast(`Opened ${script_label(parsed.script.name)}`);
}

function script_label(name) {
  return `"${name}"`;
}

async function readFile(file) {
  if (!file) return;
  if (file.size > LIMITS.MAX_SCRIPT_CHARS * 4 && !/registry/i.test(file.name)) {
    await alertDialog('File too large', `${file.name} is larger than an Alto script can be.`);
    return;
  }
  const text = await file.text();
  await openText(text, file.name);
}

async function newScript() {
  if (isDirty() && !(await confirmDialog('Start a new script?', 'The current blocks have not been saved to a file.', 'Start new', true))) return;
  loadParsedScript(parseScriptText(JSON.stringify(STARTER)).script, null);
}

// ------------------------------------------------------------------------------------------ saving

function scriptText() {
  validateNow();
  return state.last.json;
}

function suggestedFileName() {
  if (state.fileName) return state.fileName;
  const slug = String(state.meta.name || 'script').toLowerCase().replace(/[^a-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 60) || 'script';
  return slug + '.alto.json';
}

async function confirmSaveWithErrors() {
  validateNow();
  const errors = state.last.diagnostics.filter((d) => d.severity === 'error' && !d.editorOnly).length;
  if (!errors) return true;
  return confirmDialog('Save a script with errors?', `The script has ${errors} error${errors === 1 ? '' : 's'}. Alto will refuse to run it until they are fixed. Save it anyway (for example to continue later)?`, 'Save anyway');
}

function download() {
  const text = scriptText();
  const name = suggestedFileName();
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  const a = el('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  state.fileName = name;
  markClean();
  toast(`Downloaded ${name}. Put it into <minecraft>/baritone/alto/scripts/ to use it in game.`);
}

async function downloadWithCheck() {
  if (!(await confirmSaveWithErrors())) return;
  download();
}

async function saveToFolder() {
  if (typeof window.showDirectoryPicker !== 'function') {
    toast('This browser cannot write into folders; the script is downloaded instead.', 'warn');
    return downloadWithCheck();
  }
  if (!(await confirmSaveWithErrors())) return;
  try {
    if (!state.dirHandle) {
      const go = await openDialog({
        title: "Choose Alto's scripts folder",
        build: (body) => body.append(
          el('p', { text: 'Pick the folder where Alto looks for scripts:' }),
          el('p', {}, el('code', { text: '<minecraft folder>/baritone/alto/scripts/' })),
          el('p', { class: 'muted', text: 'The browser asks you to allow editing that one folder. The editor only writes the script file you save, and remembers the folder until you close this page.' }),
        ),
        buttons: [{ label: 'Cancel', value: false }, { label: 'Choose folder…', value: true, primary: true }],
      });
      if (!go) return;
      state.dirHandle = await window.showDirectoryPicker({ id: 'alto-scripts', mode: 'readwrite' });
      updateFolderStatus();
    }
    const dir = state.dirHandle;
    if (dir.queryPermission && (await dir.queryPermission({ mode: 'readwrite' })) !== 'granted') {
      if ((await dir.requestPermission({ mode: 'readwrite' })) !== 'granted') {
        toast('Saving was not allowed by the browser.', 'warn');
        return;
      }
    }
    const name = suggestedFileName();
    let exists = false;
    try {
      await dir.getFileHandle(name);
      exists = true;
    } catch (e) {
      exists = false;
    }
    if (exists && !state.writtenThisSession.has(name)
      && !(await confirmDialog('Replace the file?', `${name} already exists in ${dir.name}. Replace it with this script?`, 'Replace', true))) {
      return;
    }
    const text = scriptText();
    const handle = await dir.getFileHandle(name, { create: true });
    const writable = await handle.createWritable();
    await writable.write(text);
    await writable.close();
    state.writtenThisSession.add(name);
    state.fileName = name;
    markClean();
    toast(`Saved ${name} in the folder "${dir.name}".${dir.name !== 'scripts' ? ' (Alto reads <minecraft>/baritone/alto/scripts/.)' : ''}`);
  } catch (e) {
    if (e && e.name === 'AbortError') return;
    await alertDialog('Saving failed', [`The script could not be written: ${e && e.message ? e.message : e}`, 'You can use "Download .alto.json" instead.']);
  }
}

function updateFolderStatus() {
  const status = $('folder-status');
  if (typeof window.showDirectoryPicker !== 'function') {
    status.textContent = 'Folder saving is not available in this browser; scripts are downloaded.';
    $('btn-change-folder').hidden = true;
    return;
  }
  status.textContent = state.dirHandle ? `Saving into the folder "${state.dirHandle.name}"` : 'No folder chosen yet';
  $('btn-change-folder').hidden = !state.dirHandle;
}

async function copyJson() {
  const text = scriptText();
  try {
    await navigator.clipboard.writeText(text);
    toast('JSON copied to the clipboard.');
  } catch (e) {
    const ta = el('textarea', { class: 'offscreen', 'aria-hidden': 'true' });
    ta.value = text;
    document.body.append(ta);
    ta.select();
    const ok = document.execCommand && document.execCommand('copy');
    ta.remove();
    toast(ok ? 'JSON copied to the clipboard.' : 'Copy failed: select the text and copy it by hand.', ok ? 'info' : 'warn');
  }
}

// ------------------------------------------------------------------------------------------ script name, description, variables

function syncScriptFields() {
  $('script-name').value = state.meta.name;
  $('script-description').value = state.meta.description;
  $('file-name').textContent = state.fileName || suggestedFileName();
}

const VAR_TYPES = [['Number', 'number'], ['String', 'text'], ['Boolean', 'true/false']];

function parseInitial(type, raw) {
  if (type === 'Number') {
    const t = String(raw).trim();
    const n = Number(t);
    return t !== '' && Number.isFinite(n) ? { ok: true, value: n } : { ok: false, error: 'Enter a number such as 0, 12 or 2.5.' };
  }
  if (type === 'Boolean') return { ok: true, value: raw === true || raw === 'true' };
  const s = String(raw);
  return s.length <= LIMITS.MAX_TEXT_LENGTH ? { ok: true, value: s } : { ok: false, error: `At most ${LIMITS.MAX_TEXT_LENGTH} characters.` };
}

function nameProblem(name, taken) {
  if (!isValidName(name)) return 'Use a letter or _ first, then letters, digits or _ (at most 64 characters, no spaces).';
  if (taken.includes(name)) return `"${name}" is already used.`;
  return null;
}

async function createVariable() {
  let nameInput;
  let typeSelect;
  let valueWrap;
  let error;
  const valueField = () => valueWrap.querySelector('input, select');
  const renderValue = () => {
    valueWrap.textContent = '';
    if (typeSelect.value === 'Boolean') {
      valueWrap.append(el('select', { 'aria-label': 'Initial value' }, el('option', { value: 'false', text: 'false' }), el('option', { value: 'true', text: 'true' })));
    } else {
      valueWrap.append(el('input', { type: 'text', value: typeSelect.value === 'Number' ? '0' : '', 'aria-label': 'Initial value', spellcheck: 'false' }));
    }
  };
  const taken = variableList().map((v) => v.name);
  const r = await openDialog({
    title: 'Make a variable',
    build: (body) => {
      nameInput = el('input', { type: 'text', spellcheck: 'false', autocomplete: 'off', placeholder: 'e.g. passes' });
      typeSelect = el('select', {}, VAR_TYPES.map(([v, label]) => el('option', { value: v, text: label })));
      valueWrap = el('span', { class: 'value-wrap' });
      typeSelect.addEventListener('change', renderValue);
      renderValue();
      error = el('p', { class: 'field-error', role: 'alert' });
      body.append(
        el('label', { class: 'field' }, el('span', { text: 'Name' }), nameInput),
        el('label', { class: 'field' }, el('span', { text: 'Type' }), typeSelect),
        el('label', { class: 'field' }, el('span', { text: 'Initial value' }), valueWrap),
        el('p', { class: 'muted', text: 'Variables are shared by all events and custom blocks and keep their type for the whole run.' }),
        error,
      );
      return { focus: nameInput };
    },
    buttons: [{ label: 'Cancel', value: null }, { label: 'Create', value: 'ok', primary: true }],
    onButton: (v) => {
      if (v !== 'ok') return true;
      const problem = nameProblem(nameInput.value.trim(), taken);
      const initial = parseInitial(typeSelect.value, valueField().value);
      error.textContent = problem || (initial.ok ? '' : initial.error);
      return !problem && initial.ok;
    },
  });
  if (r !== 'ok') return;
  const type = typeSelect.value;
  const initial = parseInitial(type, valueField().value).value;
  const v = state.ws.getVariableMap().createVariable(nameInput.value.trim(), type);
  state.meta.variables.push({ id: v.getId(), initial });
  renderVariables();
  refreshToolbox();
  scheduleValidate();
  scheduleAutosave();
}

function renderVariables() {
  const list = $('variable-list');
  list.textContent = '';
  const vars = orderedVariables(state.ws, state.meta);
  $('variables-empty').hidden = vars.length > 0;
  for (const v of vars) {
    const type = v.getType() || 'Number';
    const initial = initialOf(state.meta, v);
    let input;
    if (type === 'Boolean') {
      input = el('select', { 'aria-label': `Initial value of ${v.getName()}` },
        el('option', { value: 'false', text: 'false' }), el('option', { value: 'true', text: 'true' }));
      input.value = String(initial);
    } else {
      input = el('input', { type: 'text', value: String(initial), 'aria-label': `Initial value of ${v.getName()}`, spellcheck: 'false' });
    }
    input.addEventListener('change', () => {
      const parsed = parseInitial(type, input.value);
      if (!parsed.ok) {
        toast(parsed.error, 'warn');
        input.value = String(initialOf(state.meta, v));
        return;
      }
      const entry = state.meta.variables.find((x) => x.id === v.getId());
      if (entry) entry.initial = parsed.value;
      else state.meta.variables.push({ id: v.getId(), initial: parsed.value });
      scheduleValidate();
      scheduleAutosave();
    });
    list.append(el('li', { class: 'variable-row' },
      el('span', { class: 'variable-name', text: v.getName() }),
      el('span', { class: 'variable-type', text: VAR_TYPES.find(([t]) => t === type)?.[1] || type }),
      input,
      el('button', {
        type: 'button', class: 'small', text: 'Rename', 'aria-label': `Rename ${v.getName()}`,
        onclick: async () => {
          const taken = variableList().map((x) => x.name).filter((n) => n !== v.getName());
          const name = await promptDialog('Rename variable', 'New name', v.getName(), (t) => nameProblem(t.trim(), taken));
          if (name != null && name.trim() !== v.getName()) state.ws.getVariableMap().renameVariable(v, name.trim());
        },
      }),
      el('button', {
        type: 'button', class: 'small danger', text: 'Delete', 'aria-label': `Delete ${v.getName()}`,
        onclick: async () => {
          const uses = state.ws.getVariableMap().getVariableUsesById(v.getId()).length;
          const ok = await confirmDialog('Delete variable?', uses
            ? `"${v.getName()}" is used by ${uses} block${uses === 1 ? '' : 's'}; they are deleted too (Undo brings them back).`
            : `Delete "${v.getName()}"?`, 'Delete', true);
          if (ok) state.ws.getVariableMap().deleteVariable(v);
        },
      }),
    ));
  }
}

// ------------------------------------------------------------------------------------------ custom blocks

async function editProcedure(defBlock) {
  const existing = procedureList();
  const original = defBlock ? { name: defBlock.procName_, params: defBlock.params_.map((p) => ({ ...p })) } : { name: '', params: [] };
  const rows = original.params.map((p) => ({ name: p.name, type: p.type, old: p.name }));
  let nameInput;
  let rowsBox;
  let error;
  const renderRows = () => {
    rowsBox.textContent = '';
    if (!rows.length) rowsBox.append(el('p', { class: 'muted', text: 'No parameters. Add one to give the block a slot (an item, a number, ...).' }));
    rows.forEach((row, i) => {
      const n = el('input', { type: 'text', value: row.name, spellcheck: 'false', 'aria-label': `Parameter ${i + 1} name` });
      n.addEventListener('input', () => {
        row.name = n.value.trim();
      });
      const t = el('select', { 'aria-label': `Parameter ${i + 1} type` }, PARAM_TYPES.map((p) => el('option', { value: p, text: PARAM_LABEL[p] })));
      t.value = row.type;
      t.addEventListener('change', () => {
        row.type = t.value;
      });
      rowsBox.append(el('div', { class: 'param-row' }, n, t,
        el('button', { type: 'button', class: 'small danger', text: 'Remove', 'aria-label': `Remove parameter ${i + 1}`, onclick: () => { rows.splice(i, 1); renderRows(); } })));
    });
  };
  const r = await openDialog({
    title: defBlock ? 'Edit custom block' : 'Make a block',
    wide: true,
    build: (body) => {
      nameInput = el('input', { type: 'text', value: original.name, spellcheck: 'false', autocomplete: 'off', placeholder: 'e.g. restock' });
      rowsBox = el('div', { class: 'param-rows' });
      error = el('p', { class: 'field-error', role: 'alert' });
      renderRows();
      body.append(
        el('label', { class: 'field' }, el('span', { text: 'Block name' }), nameInput),
        el('div', { class: 'field' }, el('span', { text: 'Parameters' }), rowsBox),
        el('button', { type: 'button', class: 'small', text: '+ Add parameter', onclick: () => { rows.push({ name: '', type: 'number', old: null }); renderRows(); rowsBox.querySelector('.param-row:last-child input')?.focus(); } }),
        el('p', { class: 'muted', text: 'Inside the definition, use the parameter blocks from "My Blocks". A custom block cannot call itself (no recursion).' }),
        error,
      );
      return { focus: nameInput };
    },
    buttons: [{ label: 'Cancel', value: null }, { label: defBlock ? 'Save' : 'Create', value: 'ok', primary: true }],
    onButton: (v) => {
      if (v !== 'ok') return true;
      const name = nameInput.value.trim();
      const taken = existing.filter((p) => p.block !== defBlock).map((p) => p.name);
      let problem = nameProblem(name, taken);
      const seen = [];
      for (const row of rows) {
        problem = problem || nameProblem(row.name, seen);
        seen.push(row.name);
      }
      if (rows.length > 16) problem = problem || 'At most 16 parameters.';
      error.textContent = problem ? (problem.startsWith('"') ? problem : 'Name problem: ' + problem) : '';
      return !problem;
    },
  });
  if (r !== 'ok') return;
  const name = nameInput.value.trim();
  const params = rows.map((row) => ({ name: row.name, type: row.type }));
  const ws = state.ws;
  if (!defBlock) {
    const metrics = ws.getMetrics();
    const x = (metrics.viewLeft + 40) / ws.scale;
    const y = (metrics.viewTop + 40) / ws.scale;
    const block = Blockly.serialization.blocks.append({ type: 'alto_procedure_def', extraState: { name, params }, x, y }, ws);
    ws.centerOnBlock(block.id, true);
    toast(`Custom block "${name}" created: snap blocks under "define", then use it from My Blocks.`);
  } else {
    const renames = {};
    const types = {};
    for (const row of rows) {
      if (row.old) renames[row.name] = row.old;
      types[row.name] = row.type;
    }
    const oldToNew = Object.fromEntries(Object.entries(renames).map(([n, o]) => [o, n]));
    Blockly.Events.setGroup(true);
    try {
      withMutation(defBlock, () => {
        defBlock.procName_ = name;
        defBlock.params_ = params.map((p) => ({ ...p }));
        defBlock.updateShape_();
      });
      for (const call of ws.getAllBlocks(false).filter((b) => b.type === 'alto_call' && b.procName_ === original.name)) {
        call.setProcedure(name, params, renames);
      }
      for (const b of defBlock.getDescendants(false)) {
        if (b.type === 'alto_param' && Object.prototype.hasOwnProperty.call(oldToNew, b.paramName_)) {
          const newName = oldToNew[b.paramName_];
          b.setParam(newName, types[newName]);
        }
      }
    } finally {
      Blockly.Events.setGroup(false);
    }
  }
  refreshToolbox();
  scheduleValidate();
}

// ------------------------------------------------------------------------------------------ registry

function registryStatus() {
  const info = registryInfo();
  const where = state.registryStored === true ? 'kept in this browser' : state.registryStored === false ? 'this session only (too large for browser storage)' : '';
  $('registry-status').textContent = info.source === 'built-in'
    ? `Built-in list: ${info.items} common items and ${info.blocks} blocks. Load alto-registry.json (made in game with #export-registry) for every item, also modded ones.`
    : `Registry${info.minecraft ? ' for Minecraft ' + info.minecraft : ''}: ${plural(info.items, 'item')} and ${plural(info.blocks, 'block')}${where ? ', ' + where : ''}.`;
  $('btn-forget-registry').hidden = info.source === 'built-in';
}

function plural(n, word) {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}

function loadRegistryText(text, fileName, fromStorage = false) {
  const r = parseRegistry(text);
  if (!r.ok) {
    if (!fromStorage) alertDialog('Registry not loaded', `${fileName || 'The file'}: ${r.error}`);
    return false;
  }
  setRegistry(r.registry, fileName || 'alto-registry.json');
  if (!fromStorage) {
    state.registryStored = storageSet('registry', text);
    toast(`Registry loaded: ${plural(r.registry.items.length, 'item')}, ${plural(r.registry.blocks.length, 'block')}.`);
  } else {
    state.registryStored = true;
  }
  registryStatus();
  return true;
}

// ------------------------------------------------------------------------------------------ dark mode

function applyTheme(dark, remember) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  $('btn-theme').setAttribute('aria-pressed', String(dark));
  $('btn-theme').textContent = dark ? '☀ Light' : '☾ Dark';
  if (state.ws) state.ws.setTheme(dark ? getThemes().dark : getThemes().light);
  if (remember) storageSet('theme', dark ? 'dark' : 'light');
}

function initialDark() {
  const saved = storageGet('theme');
  if (saved === 'dark' || saved === 'light') return saved === 'dark';
  return Boolean(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

// ------------------------------------------------------------------------------------------ about

function showAbout() {
  openDialog({
    title: 'About the Alto Script Editor',
    wide: true,
    build: (body) => body.append(
      el('p', { text: 'Build Alto scripts with blocks, check them like Alto does, and save them as *.alto.json files for <minecraft>/baritone/alto/scripts/.' }),
      el('h3', { text: 'Offline and private' }),
      el('ul', {},
        el('li', { text: 'This page is one self-contained file. It loads nothing from the internet, and its Content Security Policy blocks every network request.' }),
        el('li', { text: 'Your work is autosaved only in this browser (localStorage). Files are written only where you save or download them.' }),
        el('li', { text: 'Scripts cannot run code, read files or use the network: they only contain the blocks you see.' })),
      el('h3', { text: 'Third-party software' }),
      el('p', { text: `Blockly ${BLOCKLY_VERSION} — Copyright Google LLC and Blockly contributors (now maintained by the Raspberry Pi Foundation). Licensed under the Apache License, Version 2.0. The block editor in this page is built with Blockly; no other third-party code is included.` }),
      el('details', {}, el('summary', { text: 'Apache License 2.0 (full text)' }), el('pre', { class: 'license', text: BLOCKLY_LICENSE })),
      el('p', { class: 'muted', text: 'Alto is a fork of Baritone (LGPL-3.0). This editor is part of the Alto project.' }),
    ),
  });
}

// ------------------------------------------------------------------------------------------ tabs, search, keyboard

function showTab(name) {
  for (const tab of document.querySelectorAll('[role="tab"]')) {
    const selected = tab.dataset.tab === name;
    tab.setAttribute('aria-selected', String(selected));
    tab.tabIndex = selected ? 0 : -1;
    $(tab.getAttribute('aria-controls')).hidden = !selected;
  }
}

function setupTabs() {
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => showTab(tab.dataset.tab));
    tab.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
      const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
      showTab(next.dataset.tab);
      next.focus();
    });
  });
}

function setupSearch() {
  const input = $('palette-search');
  const run = () => {
    state.searchQuery = input.value;
    const tb = state.ws.getToolbox();
    if (!tb) return;
    if (!input.value.trim()) {
      tb.clearSelection();
      return;
    }
    const item = tb.getToolboxItemById('search');
    if (tb.getSelectedItem() !== item) tb.setSelectedItem(item);
    else tb.refreshSelection();
  };
  input.addEventListener('input', run);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      input.value = '';
      run();
    }
  });
}

function isTyping(target) {
  if (!target) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

function setupKeyboard() {
  document.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (!mod || e.altKey) return;
    if (document.querySelector('dialog[open]')) return;
    const key = e.key.toLowerCase();
    if (key === 's') {
      e.preventDefault();
      saveToFolder();
      return;
    }
    if (key === 'o') {
      e.preventDefault();
      $('open-file').click();
      return;
    }
    if (isTyping(e.target)) return;
    if (key === 'z' && !e.shiftKey) {
      e.preventDefault();
      undo(false);
    } else if (key === 'y' || (key === 'z' && e.shiftKey)) {
      e.preventDefault();
      undo(true);
    }
  });
}

function undo(redo) {
  Blockly.hideChaff();
  state.ws.undo(redo);
  updateUndoButtons();
}

function setupDragAndDrop() {
  const overlay = $('drop-overlay');
  let depth = 0;
  const hasFiles = (e) => e.dataTransfer && [...e.dataTransfer.types].includes('Files');
  window.addEventListener('dragenter', (e) => {
    if (!hasFiles(e)) return;
    depth++;
    overlay.hidden = false;
  });
  window.addEventListener('dragleave', (e) => {
    if (!hasFiles(e)) return;
    depth = Math.max(0, depth - 1);
    if (!depth) overlay.hidden = true;
  });
  window.addEventListener('dragover', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
  });
  window.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth = 0;
    overlay.hidden = true;
    const file = e.dataTransfer.files[0];
    if (file) readFile(file);
  });
}

// ------------------------------------------------------------------------------------------ wiring

function setupToolbar() {
  $('btn-new').addEventListener('click', newScript);
  $('btn-open').addEventListener('click', () => $('open-file').click());
  $('open-file').addEventListener('change', (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    readFile(file);
  });
  const examples = $('examples');
  for (const ex of EXAMPLES) examples.append(el('option', { value: ex.file, text: ex.title }));
  examples.addEventListener('change', async () => {
    const ex = EXAMPLES.find((x) => x.file === examples.value);
    examples.value = '';
    if (ex) await openText(ex.json, ex.file);
  });
  $('btn-save').addEventListener('click', saveToFolder);
  $('btn-download').addEventListener('click', downloadWithCheck);
  $('btn-download-2').addEventListener('click', downloadWithCheck);
  $('btn-change-folder').addEventListener('click', async () => {
    try {
      state.dirHandle = await window.showDirectoryPicker({ id: 'alto-scripts', mode: 'readwrite' });
      state.writtenThisSession.clear();
      updateFolderStatus();
    } catch (e) {
      // cancelled
    }
  });
  $('btn-undo').addEventListener('click', () => undo(false));
  $('btn-redo').addEventListener('click', () => undo(true));
  $('btn-registry').addEventListener('click', () => $('registry-file').click());
  $('registry-file').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (file) loadRegistryText(await file.text(), file.name);
  });
  $('btn-forget-registry').addEventListener('click', () => {
    storageRemove('registry');
    setRegistry(null);
    state.registryStored = undefined;
    registryStatus();
  });
  $('btn-theme').addEventListener('click', () => applyTheme(document.documentElement.dataset.theme !== 'dark', true));
  $('btn-about').addEventListener('click', showAbout);
  $('btn-copy-json').addEventListener('click', copyJson);
  $('btn-zoom-in').addEventListener('click', () => state.ws.zoomCenter(1));
  $('btn-zoom-out').addEventListener('click', () => state.ws.zoomCenter(-1));
  $('btn-zoom-fit').addEventListener('click', () => {
    state.ws.zoomToFit();
    if (state.ws.scale > 1) state.ws.setScale(1);
  });
  $('btn-cleanup').addEventListener('click', () => state.ws.cleanUp());
  $('btn-add-variable').addEventListener('click', createVariable);
  $('btn-add-procedure').addEventListener('click', () => editProcedure(null));
  $('btn-dismiss-notes').addEventListener('click', () => {
    state.importNotes = [];
    renderProblems();
  });
  $('script-name').addEventListener('input', (e) => {
    state.meta.name = e.target.value;
    if (!state.fileName) $('file-name').textContent = suggestedFileName();
    scheduleValidate();
    scheduleAutosave();
  });
  $('script-description').addEventListener('input', (e) => {
    state.meta.description = e.target.value;
    scheduleValidate();
    scheduleAutosave();
  });
  $('btn-rename-file').addEventListener('click', async () => {
    const name = await promptDialog('File name', 'Save the script as', suggestedFileName(), (t) => {
      const v = t.trim();
      if (!/^[A-Za-z0-9 _.()+-]+$/.test(v) || v.startsWith('.') || v.includes('..')) return 'Use letters, digits, spaces and _ . ( ) + - only.';
      return null;
    });
    if (name == null) return;
    let v = name.trim();
    if (!/\.json$/i.test(v)) v += '.alto.json';
    state.fileName = v;
    syncScriptFields();
    updateDirty();
    scheduleAutosave();
  });
}

export function start() {
  applyTheme(initialDark(), false);
  setupBlockly();
  injectWorkspace();
  setupTabs();
  setupSearch();
  setupKeyboard();
  setupDragAndDrop();
  setupToolbar();
  onRegistryChange(() => registryStatus());
  const savedRegistry = storageGet('registry');
  if (savedRegistry) loadRegistryText(savedRegistry, 'saved registry', true);
  registryStatus();
  if (!storageAvailable()) $('autosave-status').textContent = 'Autosave off (browser storage unavailable)';
  if (restoreAutosave()) {
    fitView();
    syncScriptFields();
    renderVariables();
    validateNow();
  } else {
    loadParsedScript(parseScriptText(JSON.stringify(STARTER)).script, null);
  }
  updateFolderStatus();
  updateUndoButtons();
  updateDirty();
  showTab('problems');
  window.altoEditor = {
    get workspace() { return state.ws; },
    Blockly,
    get report() { return state.last; },
    openText,
    exportJson: () => scriptText(),
  };
}
