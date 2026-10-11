// Static summary of what a script may do, mirroring baritone.alto.script.check.CapabilitySummary. Conservative:
// every block counts, including branches that may never run and procedures that are never called.

import { ACTION_CAPABILITIES, CAPABILITIES, COMPUTED_TEXT, normalizeCommand } from './catalog.js';
import { compareJavaStrings, javaTrim } from './javaish.js';

export function capabilitySummary(script) {
  const sources = new Map(); // capability -> Set of sources
  const commands = [];
  const chatTexts = [];
  const triggers = [];
  const addUnique = (list, value) => {
    if (!list.includes(value)) list.push(value);
  };
  const add = (cap, source) => {
    if (!sources.has(cap)) sources.set(cap, new Set());
    sources.get(cap).add(source);
  };
  const statement = (s) => {
    switch (s.stmt) {
      case 'action': {
        for (const c of ACTION_CAPABILITIES[s.type.name]) add(c, s.type.name);
        if (s.type.name === 'command') {
          const arg = s.args.get('command');
          if (arg && arg.kind === 'text') addUnique(commands, normalizeCommand(arg.text));
        }
        if (s.type.name === 'send_chat') {
          const arg = s.args.get('text');
          const e = arg && arg.kind === 'expr' ? arg.expr : null;
          addUnique(chatTexts, e && e.kind === 'literal' && e.value.type === 'string' ? e.value.value : COMPUTED_TEXT);
        }
        break;
      }
      case 'repeat':
      case 'repeat_until':
        body(s.body);
        break;
      case 'forever':
        add('RUNS_UNTIL_STOPPED', 'forever');
        body(s.body);
        break;
      case 'if':
        body(s.then);
        body(s.otherwise);
        break;
      default:
        break;
    }
  };
  const body = (list) => list.forEach(statement);

  for (const p of script.procedures) body(p.body);
  for (const h of script.handlers) {
    if (h.event.type !== 'on_start') add('RUNS_UNTIL_STOPPED', h.event.type);
    if (h.event.type === 'on_chat') {
      add('LISTENS_TO_CHAT', h.event.type);
      addUnique(triggers, javaTrim(h.event.trigger));
    }
    body(h.body);
  }

  const capabilities = CAPABILITIES.filter(([c]) => sources.has(c)).map(([c]) => c);
  const sorted = {};
  for (const c of capabilities) sorted[c] = [...sources.get(c)].sort(compareJavaStrings);
  const sentenceOf = Object.fromEntries(CAPABILITIES);
  const sentences = capabilities.map((c) => {
    const s = sentenceOf[c];
    const text = s.charAt(0).toUpperCase() + s.substring(1);
    let details = null;
    if (c === 'RUNS_COMMANDS') details = commands;
    else if (c === 'SENDS_CHAT') details = chatTexts.map((t) => (t === COMPUTED_TEXT ? t : `"${t}"`));
    else if (c === 'LISTENS_TO_CHAT') details = triggers;
    return details != null ? `${text}: ${details.join(', ')}.` : `${text} (${sorted[c].join(', ')}).`;
  });
  return { capabilities, sources: sorted, commands, chatTexts, chatTriggers: triggers, sentences };
}
