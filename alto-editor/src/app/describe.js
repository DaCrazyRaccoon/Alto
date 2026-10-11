// Human-readable descriptions of script parts, used for the "block path" of problems.

const BLOCK_TITLES = {
  goto: 'go to', mine: 'mine', build: 'build', fill: 'fill', farm: 'farm', craft: 'craft', smelt: 'smelt', fetch: 'take out of storage',
  deposit: 'put into storage', eat: 'eat', wait: 'wait', message: 'show message', command: 'run Baritone command', send_chat: 'send chat',
  use_item_on_block: 'right-click block', set_filter: 'set filter', place_station: 'place station', pickup_station: 'pick up station',
  board_vehicle: 'get into vehicle', ride_train: 'ride train', leave_vehicle: 'get out of vehicle', take_portal: 'go through portal',
  stop: 'stop the script', repeat: 'repeat', repeat_until: 'repeat until', forever: 'forever', if: 'if', wait_until: 'wait until',
  break: 'break', call: 'custom block', set_var: 'set variable', change_var: 'change variable',
};

const FIELD_TITLES = {
  block_or_item: 'block or item', from_station: 'from station', to_station: 'to station', timeout_seconds: 'timeout',
  x1: 'x1', y1: 'y1', z1: 'z1', x2: 'x2', y2: 'y2', z2: 'z2', a: 'left value', b: 'right value',
};

export function eventTitle(h) {
  if (!h || typeof h !== 'object') return 'event';
  switch (h.event) {
    case 'on_start': return 'when the script starts';
    case 'on_chat': return `when I type "${h.trigger}"`;
    case 'on_inventory_full': return 'when my inventory is full';
    case 'on_health_below': return `when my health drops below ${h.health}`;
    case 'on_item_below': return `when ${h.item} drops below ${h.count}`;
    case 'every': return `every ${h.seconds} seconds`;
    default: return String(h.event);
  }
}

export function blockTitle(s) {
  if (!s || typeof s !== 'object') return 'block';
  if (s.block === 'call') return `custom block ${s.procedure}`;
  if (s.block === 'set_var' || s.block === 'change_var') return `${BLOCK_TITLES[s.block]} ${s.name}`;
  return BLOCK_TITLES[s.block] || String(s.block);
}

/** "every 300 seconds › if › then › stop" for a path like events[1].body[4].then[0]. */
export function describePath(data, path) {
  if (!path) return 'the whole script';
  const out = [];
  let node = data;
  for (const part of path.split('.')) {
    const m = /^(.*?)(?:\[(\d+)\])?$/.exec(part);
    const key = m[1];
    const idx = m[2] == null ? null : Number(m[2]);
    let next = node && typeof node === 'object' ? node[key] : undefined;
    if (idx != null) next = Array.isArray(next) ? next[idx] : undefined;
    if (key === 'events' && idx != null) out.push(eventTitle(next));
    else if (key === 'procedures' && idx != null) out.push(`define ${next ? next.name : '?'}`);
    else if (key === 'variables' && idx != null) out.push(`variable ${next ? next.name : '?'}`);
    else if ((key === 'body' || key === 'then' || key === 'else') && idx != null) {
      if (key !== 'body') out.push(key);
      out.push(blockTitle(next));
    } else if (key === 'args') {
      // the argument name follows
    } else if (key === 'target') {
      out.push('target');
    } else if (key === 'name' && out.length === 0) {
      out.push('script name');
    } else if (key === 'description' && out.length === 0) {
      out.push('script description');
    } else if (key === 'events' || key === 'procedures' || key === 'variables') {
      out.push(key);
    } else {
      out.push(`${FIELD_TITLES[key] || key}${idx != null ? ' #' + (idx + 1) : ''}`);
    }
    node = next;
  }
  return out.join(' › ');
}
