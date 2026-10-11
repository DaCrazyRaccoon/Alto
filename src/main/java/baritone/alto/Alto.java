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

package baritone.alto;

import baritone.Baritone;
import baritone.alto.command.AltoCommands;
import baritone.alto.core.ItemCounts;
import baritone.alto.core.ItemKey;
import baritone.alto.core.Pacer;
import baritone.alto.mc.McItems;
import baritone.alto.net.LagMonitor;
import baritone.alto.net.ServerNotice;
import baritone.alto.planner.Plan;
import baritone.alto.planner.PlanStep;
import baritone.alto.planner.Planner;
import baritone.alto.planner.PlannerOptions;
import baritone.alto.planner.RecipeBook;
import baritone.alto.planner.WorldModel;
import baritone.alto.planner.mc.VanillaRecipeSource;
import baritone.alto.planner.mc.WorldModelBuilder;
import baritone.alto.selftest.SelfTests;
import baritone.alto.station.StationManager;
import baritone.alto.storage.ContainerRecord;
import baritone.alto.storage.FetchTask;
import baritone.alto.storage.StorageManager;
import baritone.alto.supply.BuilderSupply;
import baritone.alto.task.AltoProcess;
import baritone.alto.task.AltoTask;
import baritone.alto.task.Job;
import baritone.alto.task.PlanContext;
import baritone.alto.task.Step;
import baritone.alto.task.GatherTask;
import baritone.alto.task.ShulkerTask;
import baritone.alto.upkeep.Upkeep;
import baritone.alto.hazard.HazardManager;
import baritone.alto.integration.FilterAccess;
import baritone.alto.integration.Integrations;
import baritone.alto.picker.ItemPicker;
import baritone.alto.travel.PortalManager;
import baritone.alto.supply.SupplyRun;
import baritone.api.Settings;
import baritone.api.event.events.ChatEvent;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.IPlayerContext;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Root of the Alto fork for one {@link Baritone} instance. Created by a single line at the end of the
 * {@code Baritone} constructor; everything Alto adds is reachable from here.
 * <p>
 * Feature modules plug in through small hooks instead of editing this class: {@link #addWorldModelContributor}
 * (e.g. the storage index adds known chests to the planner's world model) and {@link #addStepTaskFactory}
 * (e.g. smelting turns a plan's Smelt step into a task).
 */
public final class Alto {

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final Path directory;
    private final Pacer pacer = Pacer.systemClock();
    private final LagMonitor lag = new LagMonitor();
    private final ServerNotice notice;
    private final AltoProcess process;
    private final StationManager stations;
    private final StorageManager storage;
    private Upkeep upkeep;
    private final HazardManager hazards;
    private final ItemPicker picker;
    private final PortalManager portals;
    private final SupplyRun supplyRun;
    private final baritone.alto.scriptmc.ScriptManager scripts;
    private baritone.alto.safety.SafetyStops safety;
    private final baritone.alto.task.PlatformPauseProcess platformPause;
    private final List<FilterAccess> filterAccess = new CopyOnWriteArrayList<>();
    private final VanillaRecipeSource vanillaRecipes = new VanillaRecipeSource();
    private final BuilderSupply builderSupply;
    private final SelfTests selfTests;
    private final List<Consumer<WorldModel.Builder>> worldModelContributors = new CopyOnWriteArrayList<>();
    private final List<BiFunction<PlanStep, PlanContext, AltoTask>> stepTaskFactories = new CopyOnWriteArrayList<>();
    private final List<Consumer<Alto>> tickListeners = new CopyOnWriteArrayList<>();
    private final List<Supplier<Map<ItemKey, String>>> recipePreferences = new CopyOnWriteArrayList<>();
    private final List<Function<ItemKey, String>> recipeHints = new CopyOnWriteArrayList<>();
    private final Map<String, Function<List<String>, AltoTask>> scriptActions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Function<java.util.Set<Integer>, java.util.Optional<net.minecraft.world.entity.Entity>> corpseLocator = skip -> java.util.Optional.empty();

    private Alto(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.directory = baritone.getDirectory().resolve("alto");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            AltoLog.info("could not create " + directory + ": " + e);
        }
        this.notice = new ServerNotice(directory);
        this.stations = new StationManager(this);
        this.storage = new StorageManager(this);
        this.process = new AltoProcess(baritone, this);
        this.builderSupply = new BuilderSupply(this);
        this.selfTests = new SelfTests(this);
        this.hazards = new HazardManager(this);
        this.picker = new ItemPicker(this);
        this.portals = new PortalManager(this);
        this.supplyRun = new SupplyRun(this);
        this.scripts = new baritone.alto.scriptmc.ScriptManager(this);
        this.platformPause = new baritone.alto.task.PlatformPauseProcess(baritone);
        // Vanilla fallback: the item in the inventory slot under the mouse (integrations such as EMI come first).
        this.picker.addProvider((mx, my) -> net.minecraft.client.Minecraft.getInstance().screen
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s && s.getSlotUnderMouse() != null
                ? s.getSlotUnderMouse().getItem() : net.minecraft.world.item.ItemStack.EMPTY);
        addStepTaskFactory((step, pctx) -> step instanceof PlanStep.Fetch f
                ? new FetchTask(this, ContainerRecord.Key.parse(f.storageId()), f.item(), f.count()) : null);
        addStepTaskFactory((step, pctx) -> step instanceof PlanStep.UnpackShulker u
                ? new ShulkerTask(this, ShulkerTask.Mode.UNPACK, u.item()::equals,
                u.item().shortName(), u.count(), pctx.forbiddenArea) : null);
        this.upkeep = new Upkeep(this);
        addStepTaskFactory((step, pctx) -> step instanceof PlanStep.Gather g
                ? new GatherTask(this, g.item(), g.count(), g.blocks()) : null);
    }

    /** Called once from the {@code Baritone} constructor. */
    public static Alto create(Baritone baritone) {
        Alto alto = new Alto(baritone);
        baritone.getPathingControlManager().registerProcess(alto.process);
        baritone.getPathingControlManager().registerProcess(alto.platformPause);
        alto.safety = new baritone.alto.safety.SafetyStops(alto);
        baritone.getGameEventHandler().registerEventListener(alto.new Listener());
        AltoCommands.registerAll(baritone, alto);
        Integrations.initAll(alto);
        alto.hazards.reload();
        return alto;
    }

    // ------------------------------------------------------------------------------------------ accessors

    public Baritone baritone() {
        return baritone;
    }

    public IPlayerContext ctx() {
        return ctx;
    }

    public Path directory() {
        return directory;
    }

    public Pacer pacer() {
        return pacer;
    }

    public LagMonitor lag() {
        return lag;
    }

    public AltoProcess process() {
        return process;
    }

    public StationManager stations() {
        return stations;
    }

    public StorageManager storage() {
        return storage;
    }

    /** Integrations that see moving contraptions report here every tick (extra 8). */
    public baritone.alto.task.PlatformPauseProcess platformPause() {
        return platformPause;
    }

    public baritone.alto.safety.SafetyStops safety() {
        return safety;
    }

    public baritone.alto.scriptmc.ScriptManager scripts() {
        return scripts;
    }

    public SupplyRun supplyRun() {
        return supplyRun;
    }

    public PortalManager portals() {
        return portals;
    }

    public ItemPicker picker() {
        return picker;
    }

    public HazardManager hazards() {
        return hazards;
    }

    /** Integrations that know where filter slots are (e.g. Create). */
    public void addFilterAccess(FilterAccess access) {
        filterAccess.add(access);
    }

    /** The first filter access that finds filter slots at {@code pos}, or null. */
    public FilterAccess filterAccess(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        for (FilterAccess a : filterAccess) {
            try {
                if (!a.slots(level, pos).isEmpty()) {
                    return a;
                }
            } catch (Throwable t) {
                AltoLog.debug("filter access failed: " + t);
            }
        }
        return null;
    }

    public BuilderSupply builderSupply() {
        return builderSupply;
    }

    public SelfTests selfTests() {
        return selfTests;
    }

    public static Settings settings() {
        return Baritone.settings();
    }

    public boolean enabled() {
        return settings().altoEnabled.value;
    }

    // ------------------------------------------------------------------------------------------ planning

    public RecipeBook recipeBook() {
        return vanillaRecipes.book(ctx.world());
    }

    public PlannerOptions plannerOptions() {
        Settings s = settings();
        PlannerOptions o = new PlannerOptions();
        o.alwaysUseCraftingStation = s.alwaysUseCraftingStation.value;
        o.allowCrafting = s.altoCrafting.value;
        o.useStorage = s.altoPlannerUseStorage.value;
        o.storagePreferenceRadius = s.altoPlannerStorageRadius.value;
        o.allowGather = s.altoPlannerAllowGather.value;
        o.maxDepth = s.altoPlannerMaxDepth.value;
        o.allowPlaceStation = s.altoStations.value && s.altoPlaceStations.value;
        o.reuseExistingStations = s.altoStations.value && s.altoReuseStations.value;
        o.pickUpPlacedStations = s.altoPickUpStations.value;
        o.stationSearchRadius = s.altoStationSearchRadius.value;
        o.ticksPerClick = (int) Math.max(1, Math.round(20.0 / Math.max(0.1, s.altoClicksPerSecond.value)));
        Set<ItemKey> never = new HashSet<>();
        for (Item i : s.altoNeverConsume.value) {
            never.add(McItems.key(i));
        }
        o.neverConsume = never;
        Map<ItemKey, String> preferred = new java.util.HashMap<>();
        for (Supplier<Map<ItemKey, String>> p : recipePreferences) {
            try {
                preferred.putAll(p.get());
            } catch (Throwable t) {
                AltoLog.debug("recipe preferences failed: " + t);
            }
        }
        o.preferredRecipes = preferred;
        return o;
    }

    /** Lines that explain how missing items are made (from integrations such as EMI), for chat output. */
    public List<String> hintsFor(ItemCounts missing) {
        List<String> out = new ArrayList<>();
        missing.forEach((item, n) -> {
            for (Function<ItemKey, String> h : recipeHints) {
                try {
                    String line = h.apply(item);
                    if (line != null) {
                        out.add(line);
                        break;
                    }
                } catch (Throwable t) {
                    AltoLog.debug("recipe hint failed: " + t);
                }
            }
        });
        return out;
    }

    /** Allowed fuels in preference order with their burn time (ticks), from setting altoAllowedFuels. */
    public Map<String, Integer> fuels() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Item item : settings().altoAllowedFuels.value) {
            int burn = new ItemStack(item).getBurnTime(RecipeType.SMELTING);
            if (burn > 0) {
                out.put(McItems.key(item).id(), burn);
            }
        }
        return out;
    }

    public WorldModel worldModel(ItemCounts reserved) {
        return WorldModelBuilder.build(this, reserved, java.util.Set.of());
    }

    public Plan plan(ItemCounts targets, ItemCounts reserved) {
        return plan(targets, reserved, java.util.Set.of());
    }

    /** Plans with some blocks excluded from mining (e.g. the blocks a build is placing). */
    public Plan plan(ItemCounts targets, ItemCounts reserved, java.util.Set<String> neverMineBlocks) {
        WorldModel model = WorldModelBuilder.build(this, reserved, neverMineBlocks);
        return new Planner(recipeBook(), plannerOptions()).plan(targets, model);
    }

    public boolean settingsAllowGather() {
        return settings().altoPlannerAllowGather.value;
    }

    // ------------------------------------------------------------------------------------------ extension hooks

    /** Finds your nearest own corpse (Corpse integration). */
    /** The Corpse integration: your nearest corpse, skipping the entity ids given (corpses already found empty). */
    public void setCorpseLocator(Function<java.util.Set<Integer>, java.util.Optional<net.minecraft.world.entity.Entity>> locator) {
        corpseLocator = locator;
    }

    public java.util.Optional<net.minecraft.world.entity.Entity> nearestOwnCorpse() {
        return corpseLocator.apply(java.util.Set.of());
    }

    public java.util.Optional<net.minecraft.world.entity.Entity> nearestOwnCorpse(java.util.Set<Integer> skip) {
        return corpseLocator.apply(skip);
    }

    public boolean corpseSupport() {
        return Integrations.isActive("Corpse");
    }

    /** Script actions that need a mod (e.g. "ride_train", "board_train_seat" from the Create integration). */
    public Map<String, Function<List<String>, AltoTask>> scriptActions() {
        return scriptActions;
    }

    /** Preferred recipe ids per output item (e.g. EMI recipe tree defaults). */
    public void addRecipePreferences(Supplier<Map<ItemKey, String>> source) {
        recipePreferences.add(source);
    }

    /** A one-line explanation of how an item is made, or null. */
    public void addRecipeHint(Function<ItemKey, String> hint) {
        recipeHints.add(hint);
    }

    public void addWorldModelContributor(Consumer<WorldModel.Builder> contributor) {
        worldModelContributors.add(contributor);
    }

    public void contributeToWorldModel(WorldModel.Builder b) {
        for (Consumer<WorldModel.Builder> c : worldModelContributors) {
            try {
                c.accept(b);
            } catch (Throwable t) {
                AltoLog.exception("world model contributor failed", t);
            }
        }
    }

    public void addStepTaskFactory(BiFunction<PlanStep, PlanContext, AltoTask> factory) {
        stepTaskFactories.add(factory);
    }

    /** A task for plan steps that are not crafting/station steps (fetch, smelt, gather, unpack). */
    public AltoTask tasksFor(PlanStep step, PlanContext pctx) {
        for (BiFunction<PlanStep, PlanContext, AltoTask> f : stepTaskFactories) {
            AltoTask t = f.apply(step, pctx);
            if (t != null) {
                return t;
            }
        }
        return new AltoTask(this) {
            @Override
            public String name() {
                return step.describe();
            }

            @Override
            public Step tick(boolean calcFailed) {
                return Step.failure("this build of Alto cannot do this step yet");
            }
        };
    }

    public void addTickListener(Consumer<Alto> listener) {
        tickListeners.add(listener);
    }

    private final Map<Consumer<Alto>, Integer> listenerFailures = new java.util.HashMap<>();

    /**
     * A tick listener threw. A one-off is logged; a listener that keeps failing (20 ticks in a row) or does not match
     * the installed mod version at all (LinkageError) is switched off once, with one message, and Alto keeps going.
     */
    private void listenerFailed(Consumer<Alto> l, Throwable t) {
        int n = listenerFailures.merge(l, 1, Integer::sum);
        String cls = l.getClass().getName();
        int i = cls.indexOf(".integration.");
        String part = i < 0 ? "a part of Alto" : "Alto's " + cls.substring(i + 13, cls.indexOf('.', i + 13) < 0 ? cls.length() : cls.indexOf('.', i + 13)) + " support";
        if (t instanceof LinkageError || n >= 20) {
            tickListeners.remove(l);
            listenerFailures.remove(l);
            AltoLog.exception("Switched off " + part + " (it kept failing; another version of that mod?). Everything else keeps working", t);
        } else if (n == 1) {
            AltoLog.exception("Alto tick listener failed", t);
        }
    }

    /** Starts a job (top level, replaces what Alto is doing). */
    public void startJob(Job job) {
        if (!enabled()) {
            AltoLog.warn("Alto is switched off (#set altoEnabled true to switch it on).");
            job.onFinish().accept(false, "Alto is switched off");
            return;
        }
        process.start(job);
    }

    public void onJobFinished(Job job, boolean success, String message) {
        // hook for the HUD / scripts (later milestones)
    }

    // ------------------------------------------------------------------------------------------ events

    private void applyPacing() {
        Settings s = settings();
        pacer.configure(Pacer.Kind.CONTAINER_CLICK, s.altoClicksPerSecond.value, 1);
        pacer.configure(Pacer.Kind.RECIPE_PLACE, s.altoRecipePlacementsPerSecond.value, 1);
        pacer.configure(Pacer.Kind.BLOCK_USE, s.altoBlockUsesPerSecond.value, 1);
        pacer.configure(Pacer.Kind.CONTAINER_OPEN, s.altoContainerOpensPerSecond.value, 1);
        pacer.configure(Pacer.Kind.OTHER, 2.0, 1);
        pacer.slowDown(lag.slowDownFactor(s));
    }

    private final class Listener implements AbstractGameEventListener {
        private int tick;

        @Override
        public void onTick(TickEvent event) {
            if (event.getType() != TickEvent.Type.IN) {
                return;
            }
            if (++tick % 10 == 0) {
                applyPacing();
            }
            for (Consumer<Alto> l : tickListeners) {
                try {
                    l.accept(Alto.this);
                    listenerFailures.remove(l);
                } catch (Throwable t) {
                    listenerFailed(l, t);
                }
            }
            selfTests.tick();
        }

        @Override
        public void onWorldEvent(WorldEvent event) {
            if (event.getState() == EventState.POST && event.getWorld() != null) {
                lag.reset();
                notice.onJoin();
            }
        }

        @Override
        public void onSendChatMessage(ChatEvent event) {
            // Only text you type yourself reaches this event; a script's chat trigger is handled locally.
            if (scripts.onTypedChat(event.getMessage())) {
                event.cancel();
            }
        }

        @Override
        public void onReceivePacket(PacketEvent event) {
            lag.onPacket();
            if (event.getPacket() instanceof ClientboundSetTimePacket time) {
                lag.onServerTime(time.getGameTime());
            }
        }
    }

    private static volatile Runnable settingsScreenOpener = () -> AltoLog.chat("The settings screen needs the NeoForge part of Alto.");
    private static volatile Runnable scriptScreenOpener = () -> AltoLog.chat("The script screen needs the NeoForge part of Alto.");

    /** The loader part registers how to open the settings screen (extra 11) and the script runner (M5). */
    public static void setScreenOpeners(Runnable settings, Runnable scripts) {
        settingsScreenOpener = settings;
        scriptScreenOpener = scripts;
    }

    public void openSettingsScreen() {
        // Commands run while the chat screen is open; open after the chat has closed.
        net.minecraft.client.Minecraft.getInstance().tell(() -> net.minecraft.client.Minecraft.getInstance().tell(settingsScreenOpener));
    }

    public void openScriptScreen() {
        net.minecraft.client.Minecraft.getInstance().tell(() -> net.minecraft.client.Minecraft.getInstance().tell(scriptScreenOpener));
    }

    /** Lines for the progress HUD (extra 14); empty when nothing runs. */
    public List<String> hudLines() {
        List<String> out = new ArrayList<>();
        if (safety != null && safety.tripped()) {
            out.add("§cSafety stop: " + safety.reason() + " (#resume)");
        }
        var run = scripts.current();
        if (run != null && !run.status().isEnded()) {
            out.add("§bScript " + scripts.currentName() + "§7: " + run.status().name().toLowerCase(java.util.Locale.ROOT)
                    + (run.currentBlock() == null ? "" : " - " + run.currentBlock()) + " (" + run.actionsStarted() + " actions)");
        }
        String job = process.jobLine();
        if (job != null) {
            out.add("§aAlto§7: " + job);
        }
        baritone.getPathingControlManager().mostRecentInControl()
                .filter(p -> p != process)
                .ifPresent(p -> out.add("§eBaritone§7: " + p.displayName()));
        var goal = baritone.getPathingBehavior().getGoal();
        if (goal != null && !out.isEmpty()) {
            String g = goal.toString();
            out.add("§8goal " + (g.length() > 60 ? g.substring(0, 60) + "..." : g));
        }
        return out;
    }

    /** Debug helper used by tests and #alto status. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("Alto " + (enabled() ? "on" : "OFF") + ", job: " + (process.busy() ? process.displayName() : "none"));
        lines.add(String.format("server ~%.1f TPS, ping %d ms, pacing x%.2f", lag.tps(), lag.pingMs(), pacer.slowDownFactor()));
        lines.add("stations placed by Alto: " + stations.placedCount() + ", recipes known: " + recipeBook().size());
        return lines;
    }
}
