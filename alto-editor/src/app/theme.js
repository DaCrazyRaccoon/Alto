// Light and dark Blockly themes with one block style and one category style per palette category.

import Blockly from '../blockly.js';
import { STYLE_COLOURS } from '../blocks/specs.js';

function mix(hex, other, amount) {
  const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const a = p(hex);
  const b = p(other);
  return '#' + a.map((x, i) => Math.round(x + (b[i] - x) * amount).toString(16).padStart(2, '0')).join('');
}

const FONT = 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';

function styles() {
  const blockStyles = {};
  const categoryStyles = { search_category: { colour: '#7a7f88' } };
  for (const [key, colour] of Object.entries(STYLE_COLOURS)) {
    blockStyles[key + '_blocks'] = {
      colourPrimary: colour,
      colourSecondary: mix(colour, '#ffffff', 0.35),
      colourTertiary: mix(colour, '#000000', 0.25),
      ...(key === 'events' || key === 'procedures' ? { hat: 'cap' } : {}),
    };
    categoryStyles[key + '_category'] = { colour };
  }
  // literal shadows are white boxes like in Scratch
  blockStyles.literal_blocks = { colourPrimary: STYLE_COLOURS.literal, colourSecondary: '#ffffff', colourTertiary: '#c3c7cf' };
  return { blockStyles, categoryStyles };
}

let themes = null;

export function getThemes() {
  if (themes) return themes;
  const { blockStyles, categoryStyles } = styles();
  const light = Blockly.Theme.defineTheme('alto-light', {
    name: 'alto-light',
    base: Blockly.Themes.Classic,
    blockStyles,
    categoryStyles,
    componentStyles: {
      workspaceBackgroundColour: '#f6f7f9',
      toolboxBackgroundColour: '#ffffff',
      toolboxForegroundColour: '#1f2328',
      flyoutBackgroundColour: '#eceef2',
      flyoutForegroundColour: '#1f2328',
      flyoutOpacity: 0.97,
      scrollbarColour: '#b7bcc5',
      scrollbarOpacity: 0.7,
      insertionMarkerColour: '#000000',
      insertionMarkerOpacity: 0.25,
      cursorColour: '#d0d0d0',
    },
    fontStyle: { family: FONT, weight: '600', size: 11 },
    startHats: true,
  });
  const dark = Blockly.Theme.defineTheme('alto-dark', {
    name: 'alto-dark',
    base: Blockly.Themes.Classic,
    blockStyles,
    categoryStyles,
    componentStyles: {
      workspaceBackgroundColour: '#16181d',
      toolboxBackgroundColour: '#1e2128',
      toolboxForegroundColour: '#e6e8eb',
      flyoutBackgroundColour: '#252932',
      flyoutForegroundColour: '#e6e8eb',
      flyoutOpacity: 0.97,
      scrollbarColour: '#5a606b',
      scrollbarOpacity: 0.7,
      insertionMarkerColour: '#ffffff',
      insertionMarkerOpacity: 0.3,
      cursorColour: '#d0d0d0',
      selectedGlowColour: '#ffffff',
    },
    fontStyle: { family: FONT, weight: '600', size: 11 },
    startHats: true,
  });
  themes = { light, dark };
  return themes;
}
