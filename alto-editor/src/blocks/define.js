// Registers every block of the editor with Blockly (generated from specs.js, plus the dynamic ones).

import Blockly from '../blockly.js';
import { SPECS, blocklyName } from './specs.js';
import { registerFields } from './fields.js';
import './dynamic.js';

function argJson(name, arg) {
  const n = blocklyName(name);
  switch (arg.kind) {
    case 'value':
      return arg.check ? { type: 'input_value', name: n, check: arg.check } : { type: 'input_value', name: n };
    case 'dropdown':
      return { type: 'field_dropdown', name: n, options: arg.options.map(([label, value]) => [label, value]) };
    case 'text':
      return { type: 'field_alto_text', name: n, text: arg.default, emptyText: arg.emptyText || '' };
    case 'number':
    case 'numfield':
      return { type: 'field_number', name: n, value: arg.default };
    case 'textfield':
      return { type: 'field_input', name: n, text: arg.default, spellcheck: false };
    case 'boolfield':
      return { type: 'field_dropdown', name: n, options: [['true', 'TRUE'], ['false', 'FALSE']] };
    case 'id':
    case 'idfield':
      return {
        type: 'field_alto_id', name: n, text: arg.default, registry: arg.registry,
        emptyText: arg.emptyText || '', allowEmpty: Boolean(arg.allowEmpty), spellcheck: false,
      };
    case 'variable':
      return { type: 'field_variable', name: n, variable: null, variableTypes: [arg.varType], defaultType: arg.varType };
    case 'statements':
      return { type: 'input_statement', name: n };
    default:
      throw new Error('unknown arg kind ' + arg.kind);
  }
}

/** The Blockly JSON definition of a static spec. */
export function blockDefinition(spec) {
  const def = {
    type: spec.type,
    style: (spec.style || spec.category) + '_blocks',
    tooltip: spec.tooltip,
    inputsInline: true,
  };
  const used = new Set();
  let message = '';
  const args = [];
  for (const part of spec.message.split(/(\{[a-z_0-9]+\})/)) {
    const m = /^\{([a-z_0-9]+)\}$/.exec(part);
    if (m) {
      used.add(m[1]);
      args.push(argJson(m[1], spec.args[m[1]]));
      message += '%' + args.length;
    } else {
      message += part.replace(/%/g, '%%');
    }
  }
  def.message0 = message.trim();
  def.args0 = args;
  let line = 1;
  for (const [name, arg] of Object.entries(spec.args)) {
    if (used.has(name)) continue;
    if (arg.kind !== 'statements') throw new Error(`${spec.type}: ${name} is not in the message`);
    if (name === 'else' && spec.elseLabel) {
      def['message' + line] = spec.elseLabel;
      def['args' + line] = [];
      line++;
    }
    def['message' + line] = '%1';
    def['args' + line] = [argJson(name, arg)];
    line++;
  }
  switch (spec.shape) {
    case 'hat':
      def.nextStatement = null;
      break;
    case 'statement':
    case 'c':
      def.previousStatement = null;
      def.nextStatement = null;
      break;
    case 'reporter':
      def.output = spec.output ?? null;
      break;
    default:
      throw new Error('unknown shape ' + spec.shape);
  }
  return def;
}

let defined = false;
export function defineBlocks() {
  if (defined) return;
  defined = true;
  registerFields();
  Blockly.common.defineBlocksWithJsonArray(SPECS.filter((s) => !s.dynamic).map(blockDefinition));
}
