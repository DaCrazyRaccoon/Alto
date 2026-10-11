// Blockly core for both Node (tests, CommonJS build without named ESM exports) and the browser bundle.
import * as ns from 'blockly/core';

const Blockly = ns.default && ns.default.FieldTextInput ? ns.default : ns;
export default Blockly;
