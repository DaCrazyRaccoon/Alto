/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.storage;

import baritone.Baritone;
import baritone.alto.Alto;
import baritone.alto.AltoLog;
import baritone.alto.container.MenuView;
import baritone.alto.container.SlotRole;
import baritone.alto.container.mc.ContainerAdapter;
import baritone.alto.container.mc.ContainerAdapters;
import baritone.alto.container.mc.MenuReader;
import baritone.alto.core.ItemCounts;
import baritone.alto.mc.McItems;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Feeds the {@link StorageIndex} from menus the client really has open (client thread only).
 * <p>
 * Which block a menu belongs to: the block Alto just clicked ({@link #expect}), otherwise the block under your
 * crosshair when the menu appeared. Either way the guess is validated (block type must match the menu type and
 * size; a double chest must have its partner), and if it does not fit, nothing is recorded. Contents are recorded
 * when the contents arrive, every second while open, and right before Alto closes a menu. Containers whose block is
 * broken or replaced are forgotten.
 */
public final class StorageRecorder {

    private final Alto alto;
    private final StorageManager manager;
    private AbstractContainerMenu lastMenu;
    private BlockPos expected;
    private int expectedAge = 1000;
    private ContainerRecord.Key key;
    private ContainerRecord.Key partner;
    private BlockPos pos;
    private int open;
    /** Ticks since the use key was last held: a menu opened by a hotkey (backpack, toolbox) is not the block you look at. */
    private int sinceUse = 1000;

    StorageRecorder(Alto alto, StorageManager manager) {
        this.alto = alto;
        this.manager = manager;
        alto.baritone().getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onBlockChange(BlockChangeEvent event) {
                blocksChanged(event);
            }
        });
    }

    /** Alto is about to open the container at {@code pos}. */
    public void expect(BlockPos pos) {
        this.expected = pos.immutable();
        this.expectedAge = 0;
    }

    void tick() {
        expectedAge++;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !Baritone.settings().altoEnabled.value) {
            return;
        }
        sinceUse = Minecraft.getInstance().options.keyUse.isDown() ? 0 : Math.min(1000, sinceUse + 1);
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu) {
            lastMenu = null;
            key = null;
            return;
        }
        if (menu != lastMenu) {
            lastMenu = menu;
            open = 0;
            resolve(menu);
        }
        open++;
        if (key != null && menu.getStateId() > 0 && (open == 2 || open % 20 == 0)) {
            record(menu);
        }
    }

    /** Record the open menu now (Alto calls this right before closing). */
    public void recordNow() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && key != null && p.containerMenu == lastMenu && p.containerMenu.getStateId() > 0) {
            record(p.containerMenu);
        }
    }

    private void resolve(AbstractContainerMenu menu) {
        key = null;
        partner = null;
        pos = null;
        Level level = alto.ctx().world();
        if (level == null) {
            return;
        }
        BlockPos candidate = null;
        java.util.Optional<BlockPos> own = ContainerAdapters.forMenu(menu).blockPos(menu);
        if (own.isPresent()) {
            candidate = own.get(); // the menu knows its block
        } else if (expected != null && expectedAge < 200) {
            candidate = expected;
        } else if (sinceUse <= 10) {
            HitResult hit = Minecraft.getInstance().hitResult;
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                candidate = bhr.getBlockPos();
            }
        }
        expected = null;
        if (candidate == null) {
            return;
        }
        BlockState state = level.getBlockState(candidate);
        Block block = state.getBlock();
        String dim = manager.dimension();
        if (block instanceof EnderChestBlock) {
            if (menu instanceof ChestMenu cm && cm.getRowCount() == 3) {
                key = ContainerRecord.Key.ENDER;
                pos = candidate;
            }
            return;
        }
        if (!fits(menu, state)) {
            AltoLog.debug("not indexing " + MenuReader.menuTypeId(menu) + ": does not match the block at " + candidate.toShortString());
            return;
        }
        ContainerAdapter adapter = ContainerAdapters.forMenu(menu);
        if (!adapter.indexable(menu)) {
            return;
        }
        pos = candidate;
        key = new ContainerRecord.Key(dim, candidate.getX(), candidate.getY(), candidate.getZ());
        BlockPos other = connectedChest(level, candidate, state);
        if (other != null) {
            ContainerRecord.Key otherKey = new ContainerRecord.Key(dim, other.getX(), other.getY(), other.getZ());
            // Canonical key: the lower coordinate, so both halves always map to the same record.
            if (other.asLong() < candidate.asLong()) {
                partner = key;
                key = otherKey;
            } else {
                partner = otherKey;
            }
        }
    }

    /**
     * The other half of a double chest (vanilla, or any modded chest with the same "type"/"facing" properties such as
     * Sophisticated Storage), or null. Both halves are stored under one canonical key so contents count once.
     */
    static BlockPos connectedChest(Level level, BlockPos pos, BlockState state) {
        ChestType type = null;
        net.minecraft.core.Direction facing = null;
        for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
            Object v = state.getValue(prop);
            if (prop.getName().equals("type") && v instanceof ChestType t) {
                type = t;
            } else if (prop.getName().equals("facing") && v instanceof net.minecraft.core.Direction d && d.getAxis().isHorizontal()) {
                facing = d;
            }
        }
        if (type == null || type == ChestType.SINGLE || facing == null) {
            return null;
        }
        BlockPos other = pos.relative(type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
        return level.getBlockState(other).getBlock() == state.getBlock() ? other : null;
    }

    /** Does this menu plausibly belong to this block? */
    private static boolean fits(AbstractContainerMenu menu, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof ChestBlock) {
            boolean dbl = state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
            return menu instanceof ChestMenu cm && cm.getRowCount() == (dbl ? 6 : 3);
        }
        if (b instanceof BarrelBlock) {
            return menu instanceof ChestMenu cm && cm.getRowCount() == 3;
        }
        if (b instanceof ShulkerBoxBlock) {
            return menu instanceof ShulkerBoxMenu;
        }
        if (b instanceof HopperBlock) {
            return menu instanceof HopperMenu;
        }
        if (b instanceof DispenserBlock) {
            return menu instanceof DispenserMenu;
        }
        // Modded containers: the block must have a block entity, and the menu must have storage slots.
        if (!state.hasBlockEntity()) {
            return false;
        }
        for (Slot s : menu.slots) {
            if (!(s.container instanceof net.minecraft.world.entity.player.Inventory) && s.container instanceof Container) {
                return true;
            }
        }
        return menu instanceof ChestMenu;
    }

    private void record(AbstractContainerMenu menu) {
        LocalPlayer p = Minecraft.getInstance().player;
        MenuView v = MenuReader.read(menu, p.getInventory());
        ContainerRecord r = new ContainerRecord(key);
        r.partner = partner;
        Level level = alto.ctx().world();
        BlockState state = pos != null && level != null ? level.getBlockState(pos) : null;
        r.blockId = state == null ? "" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        r.adapter = v.adapter();
        ItemCounts nested = new ItemCounts();
        int total = 0;
        int free = 0;
        for (int i = 0; i < v.slots().size(); i++) {
            if (v.slot(i).role() != SlotRole.STORAGE) {
                continue;
            }
            total++;
            if (v.slot(i).isEmpty()) {
                free++;
            } else {
                McItems.addShulkerContents(menu.slots.get(i).getItem(), nested);
            }
        }
        r.contents = v.contents(java.util.EnumSet.of(SlotRole.STORAGE));
        r.nested = nested;
        r.totalSlots = total;
        r.freeSlots = free;
        r.lastSeenMillis = System.currentTimeMillis();
        ContainerAdapter adapter = ContainerAdapters.forMenu(menu);
        r.depositAllowed = adapter.depositAllowed(menu) && !r.blockId.startsWith("lootr:");
        ContainerRecord old = manager.index().get(key);
        if (old != null) {
            r.label = old.label;
        }
        if (!r.equals(old)) {
            manager.index().put(r);
        }
    }

    private void blocksChanged(BlockChangeEvent event) {
        if (!Baritone.settings().altoEnabled.value) {
            return;
        }
        String dim = manager.dimension();
        StorageIndex index = manager.index();
        for (Pair<BlockPos, BlockState> change : event.getBlocks()) {
            BlockPos p = change.first();
            ContainerRecord.Key k = new ContainerRecord.Key(dim, p.getX(), p.getY(), p.getZ());
            ContainerRecord r = index.get(k);
            if (r == null) {
                continue;
            }
            String now = BuiltInRegistries.BLOCK.getKey(change.second().getBlock()).toString();
            if (!now.equals(r.blockId)) {
                index.remove(k);
                AltoLog.debug("forgot " + r.describe() + " (block is now " + now + ")");
            }
        }
    }

    /** For tests and the HUD: the record key of the open menu, if known. */
    public ContainerRecord.Key currentKey() {
        return key;
    }

    static boolean hasContainer(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container;
    }
}
