// Custom Blockly fields: a text field that shows a hint when empty, and the item/block id picker (type to filter
// the registry by id or display name; free text is accepted and normalized to the id syntax).

import Blockly from '../blockly.js';
import { isValidId } from '../lang/catalog.js';
import { nameOf, searchRegistry } from '../registry.js';

/** A text field that shows `emptyText` (in italics) while its value is empty. */
export class FieldOptionalText extends Blockly.FieldTextInput {
  constructor(value, validator, config) {
    super(value, validator, config);
    this.emptyText = (config && config.emptyText) || '';
  }

  static fromJson(options) {
    return new this(options.text ?? '', undefined, options);
  }

  getText_() {
    const t = super.getText_();
    if (t != null) return t;
    const v = this.getValue();
    return v === '' && this.emptyText ? this.emptyText : null;
  }

  render_() {
    super.render_();
    const root = this.getSvgRoot();
    if (root) root.classList.toggle('altoEmptyField', this.getValue() === '' && !this.isBeingEdited_);
  }
}

/** Normalizes typed text towards an id: trimmed, lower case, spaces to underscores. */
export function normalizeTypedId(text) {
  return String(text).trim().toLowerCase().replace(/\s+/g, '_');
}

let pickerCounter = 0;

/** Item/block id field with a searchable suggestion list from the registry. */
export class FieldId extends FieldOptionalText {
  constructor(value, validator, config) {
    super(value, validator, config);
    this.registryKind = (config && config.registry) || 'item';
    this.allowEmpty = Boolean(config && config.allowEmpty);
    this.suggestEl_ = null;
    this.suggestions_ = [];
    this.active_ = -1;
    this.setTooltip(() => this.describe());
  }

  describe() {
    const value = this.getValue();
    if (!value) return this.emptyText || 'item or block id';
    const name = nameOf(value);
    if (!isValidId(value)) return `"${value}" is not a valid id: use lower case letters, digits, _ . - and an optional namespace (minecraft:oak_planks)`;
    return name ? `${name} (${value.includes(':') ? value : 'minecraft:' + value})` : value;
  }

  getValueFromEditorText_(text) {
    return normalizeTypedId(text);
  }

  render_() {
    super.render_();
    const root = this.getSvgRoot();
    const v = this.getValue();
    if (root) root.classList.toggle('altoBadId', Boolean(v) && !isValidId(v));
  }

  showEditor_(e, quietInput, manageEphemeralFocus) {
    super.showEditor_(e, quietInput, manageEphemeralFocus);
    const div = Blockly.WidgetDiv.getDiv();
    if (!div || !this.htmlInput_) return;
    const list = document.createElement('div');
    list.className = 'altoSuggest';
    list.id = 'altoSuggest' + ++pickerCounter;
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-label', this.registryKind === 'block' ? 'Blocks' : this.registryKind === 'item' ? 'Items' : 'Items and blocks');
    div.appendChild(list);
    this.suggestEl_ = list;
    this.htmlInput_.setAttribute('role', 'combobox');
    this.htmlInput_.setAttribute('aria-autocomplete', 'list');
    this.htmlInput_.setAttribute('aria-controls', list.id);
    this.htmlInput_.setAttribute('aria-expanded', 'true');
    this.onSuggestInput_ = () => this.refreshSuggestions_();
    this.htmlInput_.addEventListener('input', this.onSuggestInput_);
    // show the full list at first, the current value highlighted
    this.refreshSuggestions_(true);
  }

  refreshSuggestions_(initial) {
    if (!this.suggestEl_ || !this.htmlInput_) return;
    const query = initial ? '' : this.htmlInput_.value;
    this.suggestions_ = searchRegistry(this.registryKind, query, 60);
    const current = this.getValue();
    if (initial && current) {
      // the current value first, then the rest of the list
      const full = current.includes(':') ? current : 'minecraft:' + current;
      const match = searchRegistry(this.registryKind, full, 5).find((e) => e.id === full);
      if (match) this.suggestions_ = [match, ...this.suggestions_.filter((e) => e.id !== full)].slice(0, 60);
    }
    this.active_ = -1;
    const list = this.suggestEl_;
    list.textContent = '';
    if (this.suggestions_.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'altoSuggestEmpty';
      const typed = normalizeTypedId(this.htmlInput_.value);
      empty.textContent = typed && !isValidId(typed)
        ? 'Not a valid id. Use lower case, digits and _ . - (minecraft:oak_planks).'
        : 'No match in the registry; the typed id is used as it is.';
      list.appendChild(empty);
      return;
    }
    this.suggestions_.forEach((entry, i) => {
      const row = document.createElement('div');
      row.className = 'altoSuggestItem';
      row.setAttribute('role', 'option');
      row.id = `${list.id}-${i}`;
      const name = document.createElement('span');
      name.className = 'altoSuggestName';
      name.textContent = entry.name;
      const id = document.createElement('span');
      id.className = 'altoSuggestId';
      id.textContent = entry.id;
      row.append(name, id);
      row.addEventListener('mousedown', (ev) => {
        ev.preventDefault();
        this.choose_(entry.id);
      });
      list.appendChild(row);
    });
  }

  highlight_(index) {
    if (!this.suggestEl_) return;
    const rows = this.suggestEl_.querySelectorAll('.altoSuggestItem');
    rows.forEach((r, i) => r.classList.toggle('altoSuggestActive', i === index));
    this.active_ = index;
    if (rows[index]) {
      rows[index].scrollIntoView({ block: 'nearest' });
      this.htmlInput_?.setAttribute('aria-activedescendant', rows[index].id);
    }
  }

  choose_(id) {
    if (!this.htmlInput_) return;
    this.htmlInput_.value = id;
    this.htmlInput_.dispatchEvent(new Event('input'));
    Blockly.WidgetDiv.hideIfOwner(this);
  }

  onHtmlInputKeyDown_(e) {
    const n = this.suggestions_.length;
    if (this.suggestEl_ && n > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        this.highlight_((this.active_ + 1) % n);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        this.highlight_((this.active_ - 1 + n) % n);
        return;
      }
      if (e.key === 'Enter' && this.active_ >= 0) {
        e.preventDefault();
        this.choose_(this.suggestions_[this.active_].id);
        return;
      }
    }
    super.onHtmlInputKeyDown_(e);
  }

  widgetDispose_() {
    if (this.htmlInput_ && this.onSuggestInput_) this.htmlInput_.removeEventListener('input', this.onSuggestInput_);
    if (this.suggestEl_) this.suggestEl_.remove();
    this.suggestEl_ = null;
    this.suggestions_ = [];
    super.widgetDispose_();
  }
}

let registered = false;
export function registerFields() {
  if (registered) return;
  registered = true;
  Blockly.fieldRegistry.register('field_alto_text', FieldOptionalText);
  Blockly.fieldRegistry.register('field_alto_id', FieldId);
}
