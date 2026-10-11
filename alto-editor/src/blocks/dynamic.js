// Blocks whose shape changes: farm (range + crop list), deposit (except/only lists), join (parts), and the
// My Blocks trio (definition, call, parameter). Shape changes from the +/- buttons are undoable mutation events.

import Blockly from '../blockly.js';
import { SPEC_BY_TYPE } from './specs.js';

const svg = (body) => 'data:image/svg+xml,' + encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20"><circle cx="10" cy="10" r="8.5" fill="rgba(0,0,0,0.18)" stroke="#fff" stroke-width="1.5"/>${body}</svg>`,
);
const PLUS = svg('<path d="M10 5.5v9M5.5 10h9" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>');
const MINUS = svg('<path d="M5.5 10h9" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>');

/** Hooks the app sets (editing a custom block); tests leave them empty. */
export const hooks = { editProcedure: null };

/** Runs fn and records the shape change of the block as one undoable mutation event. */
export function withMutation(block, fn) {
  const before = Blockly.Events.BlockChange.getExtraBlockState_(block);
  const outer = Blockly.Events.getGroup();
  if (!outer) Blockly.Events.setGroup(true);
  try {
    fn();
    const after = Blockly.Events.BlockChange.getExtraBlockState_(block);
    if (before !== after) Blockly.Events.fire(new Blockly.Events.BlockChange(block, 'mutation', null, before, after));
  } finally {
    if (!outer) Blockly.Events.setGroup(false);
  }
}

/**
 * Puts a default shadow into an input (events off: the shadow is part of the shape, not a separate change).
 * Inputs listed in block.empty_ stay empty: an imported script whose value is missing keeps showing the problem,
 * also after saving and loading the workspace again.
 */
function ensureShadow(input, state) {
  if (!input || !input.connection || input.connection.getShadowState()) return;
  const block = input.getSourceBlock();
  if (block && block.empty_ && block.empty_.has(input.name)) return;
  // an input that comes back (undo, or switching an option off and on) gets its previous literal value
  const remembered = block && block.removedShadows_ ? block.removedShadows_[input.name] : null;
  if (remembered) delete block.removedShadows_[input.name];
  Blockly.Events.disable();
  try {
    input.connection.setShadowState(remembered || state);
  } finally {
    Blockly.Events.enable();
  }
}

/** {empty: [...]}: dynamic value inputs that have neither a block nor a shadow (see ensureShadow). */
function emptyState(block) {
  const empty = [];
  for (const input of block.inputList) {
    if (input.type !== Blockly.inputs.inputTypes.VALUE || !input.connection) continue;
    if (!input.connection.targetBlock() && !input.connection.getShadowState()) empty.push(input.name);
  }
  return empty.length ? { empty } : {};
}

function loadEmpty(block, state) {
  block.empty_ = new Set(Array.isArray(state && state.empty) ? state.empty.map(String) : []);
}

function removeDynamicInput(block, name) {
  const input = block.getInput(name);
  const shadow = input && input.connection ? input.connection.getShadowState(true) : null;
  if (shadow) {
    block.removedShadows_ = block.removedShadows_ || {};
    block.removedShadows_[name] = shadow;
  }
  unplugInput(block, name);
  block.removeInput(name);
  if (block.empty_) block.empty_.delete(name);
}

function button(src, alt, onClick) {
  return new Blockly.FieldImage(src, 20, 20, alt, onClick);
}

/** Moves a non-shadow block out of an input that is about to disappear. */
function unplugInput(block, name) {
  const child = block.getInputTargetBlock(name);
  if (child && !child.isShadow()) {
    child.unplug();
    if (child.rendered && child.bumpNeighbours) child.bumpNeighbours();
  }
}

/**
 * A list of value inputs PREFIX0..n-1 between a label input and a button input. `count` lives on the block in
 * `this[key]`; labels(n) gives the label text.
 */
function listShape(block, cfg) {
  const n = block[cfg.key];
  block.getField(cfg.prefix + '_TEXT').setValue(cfg.label(n), false);
  for (let i = 0; i < n; i++) {
    const name = cfg.prefix + i;
    if (!block.getInput(name)) {
      const input = block.appendValueInput(name).setCheck(cfg.check);
      ensureShadow(input, cfg.shadow());
      block.moveInputBefore(name, cfg.prefix + '_BUTTONS');
    }
  }
  for (let i = n; block.getInput(cfg.prefix + i); i++) removeDynamicInput(block, cfg.prefix + i);
  const buttons = block.getInput(cfg.prefix + '_BUTTONS');
  for (const f of [...buttons.fieldRow]) buttons.removeField(f.name, true);
  if (n > (cfg.min || 0)) {
    buttons.appendField(button(MINUS, 'remove the last ' + cfg.what, () => listChange(block, cfg, -1)), cfg.prefix + '_MINUS');
  }
  buttons.appendField(button(PLUS, 'add ' + cfg.what, () => listChange(block, cfg, +1)), cfg.prefix + '_PLUS');
}

function listChange(block, cfg, delta) {
  if (!block.isEditable() || block.isInFlyout) return;
  const n = block[cfg.key];
  if (n + delta < (cfg.min || 0) || n + delta > 64) return;
  withMutation(block, () => {
    if (delta < 0) unplugInput(block, cfg.prefix + (n - 1));
    block[cfg.key] = n + delta;
    block.updateShape_();
  });
}

const idShadow = (registry, id) => () => ({ type: registry === 'block' ? 'alto_block' : registry === 'item' ? 'alto_item' : 'alto_id', fields: { VALUE: id } });

// ------------------------------------------------------------------------------------------ farm

const FARM_CROPS = {
  key: 'crops_', prefix: 'CROP', check: 'String', what: 'crop', shadow: idShadow('any', 'minecraft:wheat'),
  label: (n) => (n ? 'only these crops:' : 'crops: all kinds'),
};

Blockly.Blocks.alto_farm = {
  init() {
    const block = this;
    this.crops_ = 0;
    this.setStyle('mining_blocks');
    this.setPreviousStatement(true);
    this.setNextStatement(true);
    this.setInputsInline(true);
    this.setTooltip(SPEC_BY_TYPE.alto_farm.tooltip);
    this.appendDummyInput('HEAD')
      .appendField('harvest and replant crops nearby within')
      .appendField(new Blockly.FieldDropdown([["Alto's default range", 'DEFAULT'], ['a range of', 'SET']], function (v) {
        block.rangeMode_ = v;
        if (block.crops_ !== undefined && block.getInput('ROW1')) block.updateShape_();
        return v;
      }), 'RANGE_MODE');
    this.appendEndRowInput('ROW1');
    this.appendDummyInput('CROP_LABEL').appendField(new Blockly.FieldLabel(''), 'CROP_TEXT');
    this.appendDummyInput('CROP_BUTTONS');
    this.rangeMode_ = 'DEFAULT';
    this.updateShape_();
  },
  saveExtraState() {
    return { crops: this.crops_, ...emptyState(this) };
  },
  loadExtraState(state) {
    loadEmpty(this, state);
    this.crops_ = Math.max(0, Number(state && state.crops) || 0);
    this.updateShape_();
  },
  updateShape_() {
    const set = this.rangeMode_ === 'SET';
    if (set && !this.getInput('RANGE')) {
      const input = this.appendValueInput('RANGE').setCheck('Number');
      ensureShadow(input, { type: 'alto_number', fields: { VALUE: 16 } });
      this.appendDummyInput('RANGE_UNIT').appendField('blocks');
      this.moveInputBefore('RANGE', 'ROW1');
      this.moveInputBefore('RANGE_UNIT', 'ROW1');
    } else if (!set && this.getInput('RANGE')) {
      removeDynamicInput(this, 'RANGE');
      this.removeInput('RANGE_UNIT');
    }
    listShape(this, FARM_CROPS);
  },
};

// ------------------------------------------------------------------------------------------ deposit

const DEPOSIT_EXCEPT = {
  key: 'except_', prefix: 'EXCEPT', check: 'String', what: 'item to keep', shadow: idShadow('item', 'minecraft:bread'),
  label: (n) => (n ? 'but keep:' : 'keep: only what Alto always keeps'),
};
const DEPOSIT_ONLY = {
  key: 'only_', prefix: 'ONLY', check: 'String', what: 'item to put away', shadow: idShadow('item', 'minecraft:cobblestone'),
  label: (n) => (n ? 'only these items:' : 'items: all'),
};

Blockly.Blocks.alto_deposit = {
  init() {
    this.except_ = 0;
    this.only_ = 0;
    this.setStyle('storage_blocks');
    this.setPreviousStatement(true);
    this.setNextStatement(true);
    this.setInputsInline(true);
    this.setTooltip(SPEC_BY_TYPE.alto_deposit.tooltip);
    this.appendDummyInput('HEAD').appendField('put my inventory into storage');
    this.appendEndRowInput('ROW1');
    this.appendDummyInput('EXCEPT_LABEL').appendField(new Blockly.FieldLabel(''), 'EXCEPT_TEXT');
    this.appendDummyInput('EXCEPT_BUTTONS');
    this.appendEndRowInput('ROW2');
    this.appendDummyInput('ONLY_LABEL').appendField(new Blockly.FieldLabel(''), 'ONLY_TEXT');
    this.appendDummyInput('ONLY_BUTTONS');
    this.updateShape_();
  },
  saveExtraState() {
    return { except: this.except_, only: this.only_, ...emptyState(this) };
  },
  loadExtraState(state) {
    loadEmpty(this, state);
    this.except_ = Math.max(0, Number(state && state.except) || 0);
    this.only_ = Math.max(0, Number(state && state.only) || 0);
    this.updateShape_();
  },
  updateShape_() {
    listShape(this, DEPOSIT_EXCEPT);
    listShape(this, DEPOSIT_ONLY);
  },
};

// ------------------------------------------------------------------------------------------ join

const JOIN_PARTS = {
  key: 'parts_', prefix: 'PART', check: null, what: 'part', shadow: () => ({ type: 'alto_text', fields: { VALUE: '' } }),
  label: () => 'join',
};

Blockly.Blocks.alto_join = {
  init() {
    this.parts_ = 2;
    this.setStyle('values_blocks');
    this.setOutput(true, 'String');
    this.setInputsInline(true);
    this.setTooltip(SPEC_BY_TYPE.alto_join.tooltip);
    this.appendDummyInput('PART_LABEL').appendField(new Blockly.FieldLabel(''), 'PART_TEXT');
    this.appendDummyInput('PART_BUTTONS');
    this.updateShape_();
  },
  saveExtraState() {
    return { parts: this.parts_, ...emptyState(this) };
  },
  loadExtraState(state) {
    loadEmpty(this, state);
    this.parts_ = Math.max(0, Number(state && state.parts) || 0);
    this.updateShape_();
  },
  updateShape_() {
    listShape(this, JOIN_PARTS);
  },
};

// ------------------------------------------------------------------------------------------ my blocks

export const PARAM_CHECK = { number: 'Number', string: 'String', boolean: 'Boolean', item: 'String', block: 'String' };
export const PARAM_LABEL = { number: 'number', string: 'text', boolean: 'true/false', item: 'item', block: 'block' };

export function paramShadow(type) {
  switch (type) {
    case 'number': return { type: 'alto_number', fields: { VALUE: 0 } };
    case 'string': return { type: 'alto_text', fields: { VALUE: '' } };
    case 'item': return { type: 'alto_item', fields: { VALUE: 'minecraft:oak_log' } };
    case 'block': return { type: 'alto_block', fields: { VALUE: 'minecraft:stone' } };
    case 'boolean': return { type: 'alto_boolean', fields: { VALUE: 'TRUE' } };
    default: return null;
  }
}

function cleanParams(params) {
  return Array.isArray(params) ? params.filter((p) => p && typeof p.name === 'string').map((p) => ({ name: p.name, type: String(p.type) })) : [];
}

Blockly.Blocks.alto_procedure_def = {
  init() {
    this.procName_ = 'my_block';
    this.params_ = [];
    this.setStyle('procedures_blocks');
    this.setNextStatement(true);
    this.setInputsInline(true);
    this.setTooltip(SPEC_BY_TYPE.alto_procedure_def.tooltip);
    this.appendDummyInput('HEAD');
    this.updateShape_();
  },
  saveExtraState() {
    return { name: this.procName_, params: this.params_.map((p) => ({ ...p })) };
  },
  loadExtraState(state) {
    this.procName_ = String((state && state.name) ?? '');
    this.params_ = cleanParams(state && state.params);
    this.updateShape_();
  },
  updateShape_() {
    const head = this.getInput('HEAD');
    for (const f of [...head.fieldRow]) head.removeField(f.name, true);
    head.appendField('define', 'DEFINE');
    head.appendField(new Blockly.FieldLabel(this.procName_, 'altoProcName'), 'PROC_NAME');
    this.params_.forEach((p, i) => {
      head.appendField(new Blockly.FieldLabel(`${p.name}: ${PARAM_LABEL[p.type] || p.type}`, 'altoParamLabel'), 'PARAM' + i);
    });
  },
  customContextMenu(options) {
    if (this.isInFlyout) return;
    options.unshift({
      text: 'Edit custom block…',
      enabled: Boolean(hooks.editProcedure),
      callback: () => hooks.editProcedure && hooks.editProcedure(this),
    });
  },
};

Blockly.Blocks.alto_call = {
  init() {
    this.procName_ = 'my_block';
    this.params_ = [];
    this.setStyle('procedures_blocks');
    this.setPreviousStatement(true);
    this.setNextStatement(true);
    this.setInputsInline(true);
    this.setTooltip(() => `Runs the custom block "${this.procName_}".`);
    this.appendDummyInput('HEAD').appendField(new Blockly.FieldLabel(this.procName_, 'altoProcName'), 'PROC_NAME');
    this.updateShape_();
  },
  saveExtraState() {
    const out = { name: this.procName_, params: this.params_.map((p) => ({ ...p })), ...emptyState(this) };
    if (this.withShadows_) {
      // only inside an edit (see setProcedure): lets Undo bring back the literal argument values
      out.shadows = {};
      for (const p of this.params_) {
        const input = this.getInput('ARG_' + p.name);
        const shadow = input && input.connection ? input.connection.getShadowState(true) : null;
        if (shadow) out.shadows[p.name] = shadow;
      }
    }
    return out;
  },
  loadExtraState(state) {
    loadEmpty(this, state);
    this.procName_ = String((state && state.name) ?? '');
    this.params_ = cleanParams(state && state.params);
    this.updateShape_();
    if (state && state.shadows) this.applyShadows_(state.shadows);
  },
  applyShadows_(shadows) {
    Blockly.Events.disable();
    try {
      for (const [name, shadow] of Object.entries(shadows)) {
        const input = this.getInput('ARG_' + name);
        if (input && input.connection) input.connection.setShadowState(shadow);
      }
    } finally {
      Blockly.Events.enable();
    }
  },
  /**
   * Changes the procedure this block calls (renames maps a new parameter name to its old one). Values of kept
   * parameters move along, literal ones too; one undoable change.
   */
  setProcedure(name, params, renames = {}) {
    this.withShadows_ = true;
    try {
      withMutation(this, () => {
        const kept = new Map();
        const shadows = {};
        for (const p of params) {
          const old = Object.prototype.hasOwnProperty.call(renames, p.name) ? renames[p.name] : p.name;
          const oldParam = this.params_.find((x) => x.name === old);
          const input = old != null ? this.getInput('ARG_' + old) : null;
          if (!input || !oldParam || oldParam.type !== p.type) continue;
          const shadow = input.connection.getShadowState(true);
          if (shadow) shadows[p.name] = shadow;
          const child = input.connection.targetBlock();
          if (child && !child.isShadow()) {
            child.unplug();
            kept.set(p.name, child);
          }
        }
        this.procName_ = name;
        this.params_ = params.map((p) => ({ ...p }));
        this.updateShape_();
        this.applyShadows_(shadows);
        for (const [paramName, child] of kept) {
          const input = this.getInput('ARG_' + paramName);
          if (input && input.connection && child.outputConnection) input.connection.connect(child.outputConnection);
        }
      });
    } finally {
      this.withShadows_ = false;
    }
  },
  updateShape_() {
    this.getField('PROC_NAME').setValue(this.procName_, false);
    const wanted = this.params_.map((p) => 'ARG_' + p.name);
    for (const input of [...this.inputList]) {
      if (input.name.startsWith('ARG_') && !wanted.includes(input.name)) removeDynamicInput(this, input.name);
    }
    this.params_.forEach((p) => {
      const name = 'ARG_' + p.name;
      let input = this.getInput(name);
      const check = Object.prototype.hasOwnProperty.call(PARAM_CHECK, p.type) ? PARAM_CHECK[p.type] : null;
      if (!input) {
        input = this.appendValueInput(name).setCheck(check).appendField(p.name, 'LABEL_' + p.name);
        const shadow = paramShadow(p.type);
        if (shadow) ensureShadow(input, shadow);
      } else if (input.connection) {
        const current = input.connection.getCheck();
        const same = (current == null && check == null) || (current && check && current.length === 1 && current[0] === check);
        if (!same) {
          unplugInput(this, name);
          Blockly.Events.disable();
          try {
            input.connection.setShadowState(null);
          } finally {
            Blockly.Events.enable();
          }
          input.setCheck(check);
          const shadow = paramShadow(p.type);
          if (shadow) ensureShadow(input, shadow);
        }
      }
    });
    // keep the parameter order
    for (let i = this.params_.length - 1; i >= 0; i--) {
      const name = 'ARG_' + this.params_[i].name;
      const next = i + 1 < this.params_.length ? 'ARG_' + this.params_[i + 1].name : null;
      this.moveInputBefore(name, next);
    }
  },
};

Blockly.Blocks.alto_param = {
  init() {
    this.paramName_ = 'x';
    this.paramType_ = 'number';
    this.setStyle('procedures_blocks');
    this.setOutput(true, 'Number');
    this.appendDummyInput('HEAD').appendField(new Blockly.FieldLabel(this.paramName_), 'PARAM_NAME');
    this.setTooltip(() => `Parameter "${this.paramName_}" (${PARAM_LABEL[this.paramType_] || this.paramType_}) of a custom block; only usable inside its definition.`);
  },
  saveExtraState() {
    return { name: this.paramName_, type: this.paramType_ };
  },
  loadExtraState(state) {
    this.paramName_ = String((state && state.name) ?? '');
    this.paramType_ = String((state && state.type) ?? 'unknown');
    this.updateShape_();
  },
  updateShape_() {
    this.getField('PARAM_NAME').setValue(this.paramName_, false);
    const check = Object.prototype.hasOwnProperty.call(PARAM_CHECK, this.paramType_) ? PARAM_CHECK[this.paramType_] : null;
    if (this.outputConnection) {
      const current = this.outputConnection.getCheck();
      const same = (current == null && check == null) || (current && check && current.length === 1 && current[0] === check);
      if (!same) this.outputConnection.setCheck(check);
    }
  },
  /** Renames/retypes this parameter reporter as one undoable change. */
  setParam(name, type) {
    withMutation(this, () => {
      this.paramName_ = name;
      this.paramType_ = type;
      this.updateShape_();
    });
  },
};
