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

package baritone.alto.upkeep;

import baritone.alto.Alto;
import baritone.alto.container.MenuGoals;
import baritone.alto.core.ItemKey;
import baritone.alto.mc.McItems;
import baritone.alto.task.AltoTask;
import baritone.alto.task.MenuGoalTask;
import baritone.alto.task.Step;
import baritone.api.utils.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TieredItem;

/**
 * Eats one item of an allowed food: puts it on the hotbar if needed, looks up (so the use key cannot open a
 * container or place a block), and holds the normal use key until the food is eaten — exactly what a player does.
 */
public final class EatTask extends AltoTask {

    private enum Phase { HOTBAR, EAT, RELEASE }

    private final Item food;
    private Phase phase = Phase.HOTBAR;
    private MenuGoalTask hotbar;
    private int startFood;
    private int startCount;
    private int ticks;

    public EatTask(Alto alto, Item food) {
        super(alto);
        this.food = food;
    }

    @Override
    public String name() {
        return "Eat " + McItems.key(food).shortName();
    }

    @Override
    public Step tick(boolean calcFailed) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = ctx.player().getInventory();
        switch (phase) {
            case HOTBAR: {
                int slot = slot(inv);
                if (slot >= 0) {
                    inv.selected = slot;
                    startFood = ctx.player().getFoodData().getFoodLevel();
                    startCount = McItems.count(inv, food);
                    phase = Phase.EAT;
                    return Step.stay();
                }
                if (McItems.count(inv, food) <= 0) {
                    return Step.failure("no " + McItems.key(food).shortName() + " left");
                }
                if (hotbar == null) {
                    ItemKey key = McItems.key(food);
                    hotbar = new MenuGoalTask(alto, "Put food on the hotbar", MenuGoals.toHotbar(key, k -> !(McItems.item(k) instanceof TieredItem)));
                }
                Step s = hotbar.tick(calcFailed);
                if (s.finished()) {
                    hotbar.cancel();
                    hotbar = null;
                    if (s.kind() == Step.Kind.FAILURE) {
                        return s;
                    }
                }
                return Step.stay();
            }
            case EAT: {
                alto.baritone().getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), -90f), true);
                if (mc.screen != null) {
                    return Step.stay();
                }
                mc.options.keyUse.setDown(true);
                if (!ctx.player().isUsingItem() && ticks % 5 == 0 && ticks > 2) {
                    ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
                }
                ticks++;
                if (McItems.count(inv, food) < startCount || ctx.player().getFoodData().getFoodLevel() > startFood) {
                    phase = Phase.RELEASE;
                }
                if (ticks > 20 * 6) {
                    mc.options.keyUse.setDown(false);
                    return Step.failure("could not eat (is the food edible right now?)");
                }
                return Step.stay();
            }
            case RELEASE:
            default:
                mc.options.keyUse.setDown(false);
                return Step.success("ate " + McItems.key(food).shortName() + ", hunger now " + ctx.player().getFoodData().getFoodLevel());
        }
    }

    private int slot(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(food)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void cancel() {
        Minecraft.getInstance().options.keyUse.setDown(false);
        if (hotbar != null) {
            hotbar.cancel();
        }
    }
}
