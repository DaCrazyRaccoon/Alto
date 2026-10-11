// Item and block names for the id pickers. The game command #export-registry writes alto-registry.json:
// {"format":"alto-registry","version":1,"minecraft":"1.21.1","exported":"<ISO time>","items":[{"id","name"}],"blocks":[...]}
// Without a loaded registry a small built-in list of common vanilla ids is used.

const v = (path, name) => ({ id: 'minecraft:' + path, name });

const COMMON_BLOCKS = [
  v('stone', 'Stone'), v('cobblestone', 'Cobblestone'), v('deepslate', 'Deepslate'), v('cobbled_deepslate', 'Cobbled Deepslate'),
  v('dirt', 'Dirt'), v('grass_block', 'Grass Block'), v('sand', 'Sand'), v('gravel', 'Gravel'), v('clay', 'Clay'),
  v('oak_log', 'Oak Log'), v('spruce_log', 'Spruce Log'), v('birch_log', 'Birch Log'), v('jungle_log', 'Jungle Log'),
  v('acacia_log', 'Acacia Log'), v('dark_oak_log', 'Dark Oak Log'), v('mangrove_log', 'Mangrove Log'), v('cherry_log', 'Cherry Log'),
  v('oak_planks', 'Oak Planks'), v('spruce_planks', 'Spruce Planks'), v('birch_planks', 'Birch Planks'),
  v('oak_leaves', 'Oak Leaves'), v('glass', 'Glass'), v('glass_pane', 'Glass Pane'), v('bricks', 'Bricks'),
  v('stone_bricks', 'Stone Bricks'), v('smooth_stone', 'Smooth Stone'), v('andesite', 'Andesite'), v('diorite', 'Diorite'),
  v('granite', 'Granite'), v('netherrack', 'Netherrack'), v('obsidian', 'Obsidian'), v('end_stone', 'End Stone'),
  v('coal_ore', 'Coal Ore'), v('iron_ore', 'Iron Ore'), v('copper_ore', 'Copper Ore'), v('gold_ore', 'Gold Ore'),
  v('redstone_ore', 'Redstone Ore'), v('lapis_ore', 'Lapis Lazuli Ore'), v('diamond_ore', 'Diamond Ore'), v('emerald_ore', 'Emerald Ore'),
  v('deepslate_iron_ore', 'Deepslate Iron Ore'), v('deepslate_diamond_ore', 'Deepslate Diamond Ore'), v('nether_quartz_ore', 'Nether Quartz Ore'),
  v('ancient_debris', 'Ancient Debris'), v('crafting_table', 'Crafting Table'), v('furnace', 'Furnace'), v('blast_furnace', 'Blast Furnace'),
  v('smoker', 'Smoker'), v('chest', 'Chest'), v('barrel', 'Barrel'), v('hopper', 'Hopper'), v('torch', 'Torch'),
  v('wheat', 'Wheat Crops'), v('carrots', 'Carrots'), v('potatoes', 'Potatoes'), v('beetroots', 'Beetroots'),
  v('sugar_cane', 'Sugar Cane'), v('pumpkin', 'Pumpkin'), v('melon', 'Melon'), v('cactus', 'Cactus'), v('bamboo', 'Bamboo'),
  v('nether_wart', 'Nether Wart'), v('farmland', 'Farmland'), v('water', 'Water'), v('lava', 'Lava'), v('air', 'Air'),
  v('white_wool', 'White Wool'), v('ladder', 'Ladder'), v('rail', 'Rail'), v('powered_rail', 'Powered Rail'),
];

const COMMON_ITEMS = [
  v('oak_planks', 'Oak Planks'), v('spruce_planks', 'Spruce Planks'), v('birch_planks', 'Birch Planks'), v('oak_log', 'Oak Log'),
  v('spruce_log', 'Spruce Log'), v('birch_log', 'Birch Log'), v('stick', 'Stick'), v('cobblestone', 'Cobblestone'), v('stone', 'Stone'),
  v('dirt', 'Dirt'), v('sand', 'Sand'), v('gravel', 'Gravel'), v('glass', 'Glass'), v('glass_pane', 'Glass Pane'), v('torch', 'Torch'),
  v('coal', 'Coal'), v('charcoal', 'Charcoal'), v('raw_iron', 'Raw Iron'), v('iron_ingot', 'Iron Ingot'), v('iron_nugget', 'Iron Nugget'),
  v('raw_copper', 'Raw Copper'), v('copper_ingot', 'Copper Ingot'), v('raw_gold', 'Raw Gold'), v('gold_ingot', 'Gold Ingot'),
  v('diamond', 'Diamond'), v('emerald', 'Emerald'), v('redstone', 'Redstone Dust'), v('lapis_lazuli', 'Lapis Lazuli'),
  v('quartz', 'Nether Quartz'), v('netherite_scrap', 'Netherite Scrap'), v('netherite_ingot', 'Netherite Ingot'),
  v('crafting_table', 'Crafting Table'), v('furnace', 'Furnace'), v('blast_furnace', 'Blast Furnace'), v('smoker', 'Smoker'),
  v('chest', 'Chest'), v('barrel', 'Barrel'), v('hopper', 'Hopper'), v('bucket', 'Bucket'), v('water_bucket', 'Water Bucket'),
  v('wooden_pickaxe', 'Wooden Pickaxe'), v('stone_pickaxe', 'Stone Pickaxe'), v('iron_pickaxe', 'Iron Pickaxe'),
  v('diamond_pickaxe', 'Diamond Pickaxe'), v('iron_axe', 'Iron Axe'), v('iron_shovel', 'Iron Shovel'), v('iron_hoe', 'Iron Hoe'),
  v('iron_sword', 'Iron Sword'), v('shears', 'Shears'), v('flint_and_steel', 'Flint and Steel'),
  v('bread', 'Bread'), v('cooked_beef', 'Steak'), v('cooked_porkchop', 'Cooked Porkchop'), v('cooked_chicken', 'Cooked Chicken'),
  v('baked_potato', 'Baked Potato'), v('golden_carrot', 'Golden Carrot'), v('apple', 'Apple'), v('carrot', 'Carrot'), v('potato', 'Potato'),
  v('beetroot', 'Beetroot'), v('wheat', 'Wheat'), v('wheat_seeds', 'Wheat Seeds'), v('beetroot_seeds', 'Beetroot Seeds'),
  v('sugar_cane', 'Sugar Cane'), v('sugar', 'Sugar'), v('paper', 'Paper'), v('book', 'Book'), v('pumpkin', 'Pumpkin'),
  v('melon_slice', 'Melon Slice'), v('bone_meal', 'Bone Meal'), v('bone', 'Bone'), v('string', 'String'), v('leather', 'Leather'),
  v('white_wool', 'White Wool'), v('feather', 'Feather'), v('arrow', 'Arrow'), v('flint', 'Flint'), v('clay_ball', 'Clay Ball'),
  v('brick', 'Brick'), v('bricks', 'Bricks'), v('stone_bricks', 'Stone Bricks'), v('smooth_stone', 'Smooth Stone'),
  v('ladder', 'Ladder'), v('rail', 'Rail'), v('minecart', 'Minecart'), v('oak_boat', 'Oak Boat'), v('slime_ball', 'Slimeball'),
  v('ender_pearl', 'Ender Pearl'), v('blaze_rod', 'Blaze Rod'), v('obsidian', 'Obsidian'), v('nether_wart', 'Nether Wart'),
  v('cactus', 'Cactus'), v('bamboo', 'Bamboo'), v('kelp', 'Kelp'), v('dried_kelp', 'Dried Kelp'), v('gunpowder', 'Gunpowder'),
];

const DIMENSIONS = [v('overworld', 'Overworld'), v('the_nether', 'The Nether'), v('the_end', 'The End')];

let state = makeState('built-in', COMMON_ITEMS, COMMON_BLOCKS, null, null);
const listeners = new Set();

function makeState(source, items, blocks, minecraft, exported) {
  const names = new Map();
  for (const e of blocks) names.set(e.id, e.name);
  for (const e of items) names.set(e.id, e.name);
  const any = [...new Map([...blocks, ...items].map((e) => [e.id, e])).values()];
  return { source, items, blocks, any, names, minecraft, exported };
}

export function onRegistryChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function registryInfo() {
  return {
    source: state.source,
    items: state.items.length,
    blocks: state.blocks.length,
    minecraft: state.minecraft,
    exported: state.exported,
  };
}

/**
 * Checks and normalizes the text of an alto-registry.json file. Returns {ok, registry?, error?}; the registry is
 * {minecraft, exported, items, blocks} with entries {id, name} (names default to the id).
 */
export function parseRegistry(text) {
  let data;
  try {
    data = JSON.parse(text);
  } catch (e) {
    return { ok: false, error: 'the file is not valid JSON' };
  }
  if (!data || typeof data !== 'object' || data.format !== 'alto-registry') {
    return { ok: false, error: 'this is not an alto-registry.json file (format must be "alto-registry")' };
  }
  if (data.version !== 1) return { ok: false, error: `unsupported registry version ${data.version}; this editor reads version 1` };
  const clean = (list, what) => {
    if (!Array.isArray(list)) throw new Error(`${what} must be an array`);
    const out = [];
    const seen = new Set();
    for (const e of list) {
      if (!e || typeof e.id !== 'string' || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(e.id) || seen.has(e.id)) continue;
      seen.add(e.id);
      out.push({ id: e.id, name: typeof e.name === 'string' && e.name.trim() ? e.name.trim().slice(0, 200) : e.id });
    }
    return out;
  };
  try {
    return {
      ok: true,
      registry: {
        minecraft: typeof data.minecraft === 'string' ? data.minecraft : null,
        exported: typeof data.exported === 'string' ? data.exported : null,
        items: clean(data.items, 'items'),
        blocks: clean(data.blocks, 'blocks'),
      },
    };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

export function setRegistry(registry, source) {
  state = registry
    ? makeState(source || 'loaded', registry.items, registry.blocks, registry.minecraft, registry.exported)
    : makeState('built-in', COMMON_ITEMS, COMMON_BLOCKS, null, null);
  for (const fn of listeners) fn(registryInfo());
}

/** Display name of an id ('oak_planks' and 'minecraft:oak_planks' both work), or null. */
export function nameOf(id) {
  if (typeof id !== 'string' || !id) return null;
  const full = id.includes(':') ? id : 'minecraft:' + id;
  return state.names.get(full) || null;
}

function listFor(kind) {
  if (kind === 'block') return state.blocks;
  if (kind === 'item') return state.items;
  if (kind === 'dimension') return DIMENSIONS;
  return state.any;
}

/** Entries matching every word of the query in the id or the name, best matches first. */
export function searchRegistry(kind, query, limit = 60) {
  const list = listFor(kind);
  const q = String(query || '').trim().toLowerCase();
  if (!q) return list.slice(0, limit);
  const words = q.split(/[\s_:]+/).filter(Boolean);
  const scored = [];
  for (const e of list) {
    const id = e.id;
    const path = id.slice(id.indexOf(':') + 1);
    const name = e.name.toLowerCase();
    const hay = id + ' ' + name;
    if (!words.every((w) => hay.includes(w))) continue;
    let score = 4;
    if (id === q || path === q || name === q) score = 0;
    else if (path.startsWith(q.replace(/\s+/g, '_')) || name.startsWith(q)) score = 1;
    else if (words.every((w) => path.split('_').some((p) => p.startsWith(w)) || name.split(' ').some((p) => p.startsWith(w)))) score = 2;
    scored.push([score, path.length, e]);
  }
  scored.sort((a, b) => a[0] - b[0] || a[1] - b[1] || (a[2].id < b[2].id ? -1 : 1));
  return scored.slice(0, limit).map((s) => s[2]);
}
