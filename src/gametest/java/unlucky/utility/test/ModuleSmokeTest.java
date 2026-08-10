package unlucky.utility.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.misc.Panic;
import unlucky.utility.client.module.modules.combat.AutoLog;
import unlucky.utility.client.module.modules.movement.AntiVoid;
import unlucky.utility.client.module.modules.misc.BibleBot;
import unlucky.utility.client.module.modules.misc.DiscordRPC;
import unlucky.utility.client.module.modules.misc.UnluckyUsers;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.player.ChestStealer;
import unlucky.utility.client.module.modules.player.NoRotate;
import unlucky.utility.client.module.modules.render.Trajectories;
import unlucky.utility.client.module.modules.world.NewChunks;
import unlucky.utility.client.util.BlockGroups;
import unlucky.utility.client.util.MixinAudit;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.PacketQueueManager;
import unlucky.utility.client.util.ProjectileAimSolver;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.TargetingUtil;
import unlucky.utility.client.util.WeatherOverrideManager;
import unlucky.utility.client.util.net.UnluckyApi;

/**
 * Enables every module in a world and renders frames while it runs — one at a time,
 * then all together.
 *
 * <p>Sibling to {@link ScreenSmokeTest}, and the same bargain: it claims nothing about
 * whether a module <em>works</em>, only that turning it on does not throw. What makes
 * that worth the runtime is the version bump. A rename is a compile error and costs
 * nothing to find; a module that throws the first time its render path runs against a
 * changed API is invisible until someone toggles it, and 128 modules is more than anyone
 * checks by hand before tagging.
 *
 * <p>Blame isolation is the reason for the one-at-a-time pass: the log line printed
 * before each module names the one that took the client down. The all-at-once pass
 * afterwards is for the failures that only exist between modules — two render hooks
 * fighting over the same pose stack, say — which the isolated pass cannot see by
 * construction.
 *
 * <p><b>The scene is load-bearing.</b> The gametest world is a frozen superflat with mob
 * spawning off, and an ESP with nothing to draw is an ESP whose interesting path never
 * runs. {@link #buildScene} places what the render modules need to have something to do,
 * and {@link #verifyScene} then asserts it is actually there — commands that fail are
 * reported to the command source and swallowed, so without the check a syntax change in
 * some future version would quietly reduce this to a test of an empty field.
 *
 * <p>Not covered, and deliberately: anything that needs input (right-click modules,
 * BookTools' screens), anything needing a second player (the nametag armor row), and
 * every module's behaviour. This is the cheap half.
 */
public class ModuleSmokeTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("unlucky-test");

	/** Ticks a module stays on in the isolated pass. Frames render between ticks — the frames are the test. */
	private static final int DWELL = 4;

	/** Ticks the everything-on pass runs for. */
	private static final int TOGETHER_DWELL = 40;

	/** How far from the player {@link #verifyScene} looks for the blocks it placed. */
	private static final int SCENE_RADIUS = 6;

	/**
	 * Modules the sweep will not touch, and why.
	 *
	 * <p>Keyed by class and not by name so that deleting or renaming one of these is a
	 * compile error. A skip list that quietly stops matching anything is the same silent
	 * rot this test exists to catch, and it fails in the direction that hurts: the module
	 * comes back into a sweep that CI runs on every push.
	 *
	 * <p>Every entry is here because enabling it reaches outside the machine. Modules that
	 * only <em>read</em> from the network stay in — {@code Capes} does one GET to
	 * api.github.com and fails soft, and it is the only cover the cape-swap render path
	 * gets.
	 */
	private static final Map<Class<? extends Module>, String> SKIPPED = Map.of(
			UnluckyUsers.class, "publishes this client's identity to api.unlucky.life",
			BibleBot.class, "fetches from bible-api.com on a timer",
			DiscordRPC.class, "opens a Discord IPC socket",
			// Not for reaching outside the machine, but for the blast radius. Its entire
			// behaviour is "leave the server", and the sweep runs inside one — a trigger
			// nobody predicted would end the world mid-pass and fail as something else
			// entirely. The defaults would not fire on a creative superflat; that is a
			// reason to expect it to pass, not a reason to bet the run on it.
			AutoLog.class, "disconnects on purpose, which would end the test world");

	/**
	 * Placed by {@link #buildScene}, checked by {@link #verifyScene}.
	 *
	 * <p>The banner comes off a {@code ColorCollection} — 26.2 folded the sixteen dyed
	 * variants of every block into one, so there is no {@code Blocks.WHITE_BANNER} any more.
	 */
	private static final Block[] SCENE_BLOCKS = {
			Blocks.CHEST,             // StorageESP
			Blocks.DIAMOND_ORE,       // XRay, Search
			Blocks.BANNER.white(),    // VanityESP, BannerData
			Blocks.BREWING_STAND};    // AutoBrew

	/**
	 * What the registry-derived groups replaced, kept verbatim as the bar they have to
	 * clear.
	 *
	 * <p>These lists were program logic until {@link BlockGroups} and AutoEat's blacklist
	 * started deriving them, and the test is the right place for them now: a rule that
	 * quietly stops matching — {@code endsWith("_ore")} against a version that renames ores,
	 * {@code Container} moving out from under the storage check — produces a smaller set,
	 * not an error. Asserting the derived set is a <em>superset</em> of what was hand-written
	 * turns "the rule looks right" into a checked fact, and leaves it free to grow.
	 */
	private static final Map<String, String[]> DERIVED_BASELINE = Map.of(
			"ores", new String[]{
					"minecraft:coal_ore", "minecraft:deepslate_coal_ore",
					"minecraft:iron_ore", "minecraft:deepslate_iron_ore",
					"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
					"minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
					"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
					"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
					"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
					"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
					"minecraft:nether_quartz_ore", "minecraft:ancient_debris"},
			"storage", new String[]{
					"minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
					"minecraft:barrel", "minecraft:hopper", "minecraft:furnace",
					"minecraft:blast_furnace", "minecraft:smoker", "minecraft:dispenser",
					"minecraft:dropper", "minecraft:shulker_box"},
			"valuables", new String[]{
					"minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_block",
					"minecraft:iron_block", "minecraft:netherite_block", "minecraft:ancient_debris",
					"minecraft:spawner", "minecraft:enchanting_table", "minecraft:beacon",
					"minecraft:shulker_box", "minecraft:amethyst_cluster", "minecraft:budding_amethyst"});

	/**
	 * Blocks each group must <em>not</em> claim.
	 *
	 * <p>A superset check cannot fail a rule that has gone too wide, and too wide is the
	 * failure that actually shipped: {@code SculkBlock extends DropExperienceBlock}, so
	 * classing ores by "drops experience" put sculk in XRay's default visible set and left
	 * ancient cities opaque while X-raying — a bad list that presents as a rendering bug.
	 */
	private static final Map<String, String[]> DERIVED_EXCLUDED = Map.of(
			"ores", new String[]{"minecraft:sculk", "minecraft:stone", "minecraft:deepslate"},
			"storage", new String[]{"minecraft:stone", "minecraft:dirt"},
			"valuables", new String[]{"minecraft:stone", "minecraft:dirt"});

	/**
	 * The six items AutoEat used to name, which {@code AutoEat.harmful} now has to recognise
	 * on its own. Suspicious stew is the seventh and is checked separately: its effects live
	 * on the stack, so a plain bowl is genuinely safe and the old list was simply wrong to
	 * ban it outright.
	 */
	private static final String[] HARMFUL_FOOD = {
			"minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato",
			"minecraft:pufferfish", "minecraft:chorus_fruit", "minecraft:chicken"};

	/**
	 * Food the rule must keep its hands off.
	 *
	 * <p>The superset checks above cannot fail a rule that answers "harmful" to everything,
	 * and a rule that did would leave AutoEat unable to eat at all — which is a worse bug
	 * than the one being guarded against, and a silent one.
	 */
	private static final String[] HARMLESS_FOOD = {
			"minecraft:bread", "minecraft:cooked_beef", "minecraft:golden_apple",
			"minecraft:carrot", "minecraft:cooked_salmon"};

	@Override
	public void runTest(ClientGameTestContext context) {
		verifyRegistryIsReadOnly(context);
		verifyMixinsApplied(context);
		verifyVisibilityMetadata(context);
		everyModuleWithNoWorld(context);

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			verifyDerivedGroups(context);
			buildScene(context, singleplayer.getServer());
			verifyTargetingAndProjectiles(context);

			// No screen: the world and the HUD are what we want rendering under each module.
			context.setScreen(() -> null);
			context.waitTicks(DWELL);

			List<Module> sweep = sweepList(context);
			eachModuleAlone(context, sweep);
			everyModuleAtOnce(context, sweep);
			panicMinimalSweep(context, sweep);
		}

		LOGGER.info("[modules] every module enabled and rendered clean");
	}

	/**
	 * Every module answers for its own server visibility, and the conditional ones answer
	 * properly.
	 *
	 * <p>{@link ServerVisibility#CONDITIONAL} is a promise to override
	 * {@link Module#isServerObservableNow()}; a module that declares it and does not is
	 * indistinguishable at runtime from {@code SERVER_OBSERVABLE}, so the mistake is invisible
	 * — the module simply gets disabled by every panic, for ever, and nobody notices it was
	 * meant to be cleverer than that. Reflection is the only thing that can tell the
	 * difference, so reflection is what asks.
	 *
	 * <p>Runs before the world, because none of it needs one.
	 */
	private void verifyVisibilityMetadata(ClientGameTestContext context) {
		List<String> problems = context.computeOnClient(mc -> {
			List<String> found = new ArrayList<>();
			for (Module module : UnluckyClient.INSTANCE.modules.all()) {
				if (module.getVisibility() != ServerVisibility.CONDITIONAL) {
					continue;
				}
				try {
					Class<?> declarer = module.getClass()
							.getMethod("isServerObservableNow").getDeclaringClass();
					if (declarer == Module.class) {
						found.add(module.getName());
					}
				} catch (NoSuchMethodException e) {
					found.add(module.getName() + " (method missing)");
				}
			}
			return found;
		});

		if (!problems.isEmpty()) {
			throw new AssertionError("CONDITIONAL modules that never say when they are observable: "
					+ String.join(", ", problems)
					+ ". Each is being treated as permanently server-visible — override "
					+ "isServerObservableNow, or declare SERVER_OBSERVABLE and mean it.");
		}
		int classified = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.all().size());
		LOGGER.info("[panic] visibility metadata holds for {} modules", classified);
	}

	/**
	 * Turns everything on, hits Panic in Minimal mode, and checks what is left standing.
	 *
	 * <p>This is the assertion the whole {@link ServerVisibility} mechanism exists to make
	 * true, and it is worth a real pass rather than a unit test of the predicate: Panic's job
	 * is a <em>side effect</em> on a hundred live modules, and the failure mode that matters —
	 * one module quietly surviving a panic — is exactly the one that reading the code does not
	 * catch. Conditionals are excluded from both halves on purpose; whether one is observable
	 * mid-sweep is by definition a question about the moment, not about the classification.
	 */
	private void panicMinimalSweep(ClientGameTestContext context, List<Module> sweep) {
		LOGGER.info("[panic] minimal sweep over {} modules", sweep.size());
		Map<Module, Boolean> before = new LinkedHashMap<>();

		context.runOnClient(mc -> {
			for (Module module : sweep) {
				before.put(module, module.isEnabled());
				module.setEnabled(true);
			}
		});
		context.waitTicks(DWELL);

		List<String> failures = context.computeOnClient(mc -> {
			Panic panic = UnluckyClient.INSTANCE.modules.get(Panic.class);
			String previousMode = panic.mode.get();
			panic.mode.set("Minimal");
			panic.fire();
			panic.mode.set(previousMode);

			List<String> problems = new ArrayList<>();
			for (Module module : sweep) {
				if (module == panic || !module.isToggleable()) {
					continue;
				}
				switch (module.getVisibility()) {
					case SERVER_OBSERVABLE -> {
						if (module.isEnabled()) {
							problems.add(module.getName() + " survived a panic");
						}
					}
					case CLIENT_ONLY -> {
						if (!module.isEnabled()) {
							problems.add(module.getName() + " was taken down by Minimal");
						}
					}
					case CONDITIONAL -> {
					}
				}
			}
			return problems;
		});

		context.runOnClient(mc -> before.forEach(Module::setEnabled));
		context.waitTick();

		if (!failures.isEmpty()) {
			throw new AssertionError("Panic Minimal disabled the wrong set: "
					+ String.join("; ", failures)
					+ ". Either a module's ServerVisibility is wrong or Panic stopped reading it.");
		}
		LOGGER.info("[panic] minimal left every client-only module standing and no other");
	}

	/**
	 * This run must not be able to write to the Unlucky registry.
	 *
	 * <p>{@code UnluckyUsers} is on by default and publishes every five seconds while
	 * connected — and singleplayer counts as connected — so before the guard existed every CI
	 * run put a fictional "Player0" into a public directory of real players. Two runs now,
	 * with the Sodium leg.
	 *
	 * <p>Asserted rather than assumed because the guard is invisible when it works: nothing
	 * in a passing log distinguishes "writes are off" from "writes went out fine". If someone
	 * later points CI at a custom API base this fails loudly, which is the correct outcome —
	 * that is a deliberate act and should be a deliberate edit.
	 */
	private void verifyRegistryIsReadOnly(ClientGameTestContext context) {
		if (context.computeOnClient(mc -> UnluckyApi.writesAllowed())) {
			throw new AssertionError("The registry would accept writes from this run. A gametest "
					+ "publishes the dev session's identity to a public directory — see "
					+ "UnluckyApi.writesAllowed.");
		}
		LOGGER.info("[registry] writes are off for this run");
	}

	/**
	 * Every mixin whose target class exists must have landed on it.
	 *
	 * <p>Narrower than it sounds, and {@code MixinAudit}'s javadoc is the place that says how
	 * narrow: this catches a mixin missing its class, not an injection missing its injection
	 * point. The latter already throws under {@code defaultRequire: 1}.
	 *
	 * <p><b>Absent targets are only forgivable when Sodium is not installed.</b> Its three
	 * mixins name their targets as strings, so nothing else in the build checks them; on an
	 * ordinary run "absent" and "Sodium renamed the class" read identically, which is why the
	 * {@code -PwithSodium} job exists. When Sodium <em>is</em> loaded there is no excuse left
	 * and every target must resolve — that run is the only thing standing between a Sodium
	 * package rename and XRay-under-Sodium dying silently and permanently.
	 */
	private void verifyMixinsApplied(ClientGameTestContext context) {
		List<MixinAudit.Result> results = context.computeOnClient(mc -> MixinAudit.run());
		boolean withSodium = FabricLoader.getInstance().isModLoaded("sodium");

		List<String> broken = new ArrayList<>();
		for (MixinAudit.Result result : results) {
			if (result.status() == MixinAudit.Status.NOT_APPLIED) {
				broken.add(result.mixin() + " -> " + result.target());
			}
			if (withSodium && result.status() == MixinAudit.Status.TARGET_ABSENT) {
				broken.add(result.mixin() + " -> " + result.target() + " (missing with Sodium loaded)");
			}
		}
		if (!broken.isEmpty()) {
			throw new AssertionError("Mixins did not apply: " + String.join(", ", broken)
					+ ". The target class loaded without our members, so every injection in "
					+ "those mixins was dropped — see MixinAudit.");
		}
		if (results.isEmpty()) {
			throw new AssertionError("Mixin audit found nothing to check — it can no longer read "
					+ "the config or the @Mixin annotations, so this test is asserting nothing.");
		}
		LOGGER.info("[mixins] {} mixin targets audited, none dropped (sodium {})",
				results.size(), withSodium ? "loaded" : "absent");
	}

	/**
	 * Checks each derived rule still covers everything the list it replaced named — and, for
	 * the food rule, that it has not gone the other way and swallowed the whole registry.
	 *
	 * <p>Runs inside the world rather than before it because of the food half: 26.2 binds
	 * item components only once a world has synced its registries, and reading them earlier
	 * throws. The block groups would answer from the title screen, which is the property
	 * that lets the picker work there.
	 */
	private void verifyDerivedGroups(ClientGameTestContext context) {
		List<String> failures = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();

			Map<String, Set<String>> groups = new LinkedHashMap<>();
			groups.put("ores", BlockGroups.ores());
			groups.put("storage", BlockGroups.storage());
			groups.put("valuables", BlockGroups.valuables());
			groups.forEach((name, ids) -> {
				LOGGER.info("[groups] {} — {} entries", name, ids.size());
				for (String expected : DERIVED_BASELINE.get(name)) {
					if (!ids.contains(expected)) {
						problems.add(name + " lost " + expected);
					}
				}
				for (String banned : DERIVED_EXCLUDED.get(name)) {
					if (ids.contains(banned)) {
						problems.add(name + " now claims " + banned);
					}
				}
			});

			for (String id : HARMFUL_FOOD) {
				if (!AutoEat.harmful(stackOf(id))) {
					problems.add("AutoEat would now eat " + id);
				}
			}
			for (String id : HARMLESS_FOOD) {
				if (AutoEat.harmful(stackOf(id))) {
					problems.add("AutoEat would now refuse " + id);
				}
			}

			// The block picker's Tags tab is only as good as this. Tags are datapack state
			// synced from the server, so "no tags" is the correct answer on the title screen
			// and a silently dead tab in a world.
			List<String> tags = BuiltInRegistries.BLOCK.getTags()
					.map(named -> named.key().location().toString()).toList();
			LOGGER.info("[groups] block tags in world — {}", tags.size());
			if (!tags.contains("minecraft:diamond_ores")) {
				problems.add("the block registry exposes no diamond_ores tag, so the Tags tab is empty");
			}

			// The stew's effects ride on the stack, so both bowls have to be judged apart.
			ItemStack plainStew = stackOf("minecraft:suspicious_stew");
			if (AutoEat.harmful(plainStew)) {
				problems.add("AutoEat would now refuse an effectless suspicious stew");
			}
			ItemStack poisonStew = stackOf("minecraft:suspicious_stew");
			poisonStew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(
					List.of(new SuspiciousStewEffects.Entry(MobEffects.POISON, 100))));
			if (!AutoEat.harmful(poisonStew)) {
				problems.add("AutoEat would now eat a poisoned suspicious stew");
			}
			return problems;
		});

		if (!failures.isEmpty()) {
			throw new AssertionError("A derived rule no longer matches what it replaced: "
					+ String.join("; ", failures)
					+ ". The rule stopped matching rather than erroring — see BlockGroups and "
					+ "AutoEat.harmful.");
		}
		LOGGER.info("[groups] block groups and the harmful-food rule both hold");
	}

	/**
	 * The targeting/projectile foundation is deliberately exercised by the real scene. Pure
	 * constant tests would not catch a selector that no longer recognises 26.2's Enemy marker,
	 * or a path whose clip context never reaches the world. The solver test also proves it is
	 * consuming the shared path implementation rather than returning an analytic guess nobody
	 * collided.
	 */
	private void verifyTargetingAndProjectiles(ClientGameTestContext context) {
		List<String> failures = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();
			TargetingUtil.Filter hostile = new TargetingUtil.Filter()
					.groups(false, true, false).range(16);
			Entity pickedHostile = TargetingUtil.select(mc.player,
					mc.level.entitiesForRendering(), hostile);
			if (!(pickedHostile instanceof Zombie)) {
				problems.add("hostile selector did not pick the scene zombie");
			}

			TargetingUtil.Filter passive = new TargetingUtil.Filter()
					.groups(false, false, true).range(16);
			Entity pickedPassive = TargetingUtil.select(mc.player,
					mc.level.entitiesForRendering(), passive);
			if (!(pickedPassive instanceof Cow)) {
				problems.add("passive selector did not pick the scene cow");
			}

			if (Math.abs(ProjectilePathUtil.ProjectileType.BOW_ARROW.initialSpeed(20) - 3.0)
					> 1.0e-9
					|| ProjectilePathUtil.ProjectileType.BOW_ARROW.initialSpeed(0) != 0.0) {
				problems.add("bow charge curve no longer reaches 0 → 3.0");
			}

			List<Vec3> multishot = new ArrayList<>(3);
			ProjectilePathUtil.multishot(new Vec3(0, 0, 1),
					new double[] { -10.0, 0.0, 10.0 }, multishot);
			if (multishot.size() != 3 || multishot.getFirst().equals(multishot.getLast())) {
				problems.add("crossbow multishot no longer produces three distinct paths");
			}
			Trajectories trajectories = UnluckyClient.INSTANCE.modules.get(Trajectories.class);
			if (trajectories.otherPlayers.get() || trajectories.firedProjectiles.get()
					|| !trajectories.accurateSimulation.get()
					|| trajectories.simulationSteps.getInt() != 300
					|| trajectories.ignoreFirst.getInt() != 3) {
				problems.add("Trajectories defaults drifted from the shared projectile contract");
			}
			if (!NewChunks.isNewEvidence(Fluids.FLOWING_WATER.defaultFluidState().createLegacyBlock())
					|| NewChunks.isNewEvidence(Fluids.WATER.defaultFluidState().createLegacyBlock())) {
				problems.add("NewChunks fluid evidence no longer distinguishes flow from source");
			}
			AntiVoid antiVoid = UnluckyClient.INSTANCE.modules.get(AntiVoid.class);
			if (!antiVoid.detection.is("Predictive") || !antiVoid.mode.is("Freeze")
					|| !antiVoid.onlyTrueVoid.get() || antiVoid.lookAhead.getInt() != 10
					|| antiVoid.minimumFall.getInt() != 3) {
				problems.add("AntiVoid defaults drifted from the safety contract");
			}
			if (unlucky.utility.client.util.DamageForecast.distanceToGround(
					mc.player, mc.player.getBoundingBox()) < 0.0) {
				problems.add("predicted-footprint support did not find the scene floor");
			}
			Object travel = new Object();
			Object rescue = new Object();
			MovementActionCoordinator.request(travel,
					MovementActionCoordinator.PRIORITY_TRAVEL, velocity -> velocity);
			MovementActionCoordinator.request(rescue,
					MovementActionCoordinator.PRIORITY_ANTI_VOID, velocity -> Vec3.ZERO);
			if (!MovementActionCoordinator.owns(rescue)
					|| MovementActionCoordinator.owns(travel)) {
				problems.add("AntiVoid no longer outranks ordinary synthetic movement");
			}
			MovementActionCoordinator.reset();
			ServerboundMovePlayerPacket movement =
					new ServerboundMovePlayerPacket.StatusOnly(true, false);
			ServerboundSwingPacket swing = new ServerboundSwingPacket(InteractionHand.MAIN_HAND);
			ServerboundAcceptTeleportationPacket teleportConfirm =
					new ServerboundAcceptTeleportationPacket(1);
			if (!PacketQueueManager.isQueueable(movement,
					PacketQueueManager.QueueMode.MOVEMENT_ONLY)
					|| PacketQueueManager.isQueueable(swing,
							PacketQueueManager.QueueMode.MOVEMENT_ONLY)
					|| !PacketQueueManager.isQueueable(swing,
							PacketQueueManager.QueueMode.MOVEMENT_AND_ACTIONS)
					|| PacketQueueManager.isQueueable(teleportConfirm,
							PacketQueueManager.QueueMode.MOVEMENT_AND_ACTIONS)) {
				problems.add("packet queue allowlist admitted critical traffic or lost gameplay traffic");
			}
			Object weatherOwner = new Object();
			WeatherOverrideManager.reset();
			WeatherOverrideManager.request(weatherOwner,
					WeatherOverrideManager.State.noWeather(true, false));
			if (WeatherOverrideManager.rainLevel(0.75f) != 0.0f
					|| WeatherOverrideManager.thunderLevel(0.75f) != 0.75f
					|| WeatherOverrideManager.weatherEffectsAllowed()) {
				problems.add("weather owner no longer preserves independent server channels");
			}
			WeatherOverrideManager.release(weatherOwner);
			ChestStealer chestStealer = UnluckyClient.INSTANCE.modules.get(ChestStealer.class);
			if (!chestStealer.mode.is("All") || chestStealer.delay.getInt() != 1
					|| chestStealer.randomDelay.getInt() != 2 || !chestStealer.quickMove.get()
					|| !chestStealer.autoClose.get() || chestStealer.closeDelay.getInt() != 2
					|| chestStealer.onlyChests.get() || chestStealer.ignoreNamed.get()
					|| !chestStealer.stopWhenFull.get()) {
				problems.add("ChestStealer defaults drifted from delayed reliable looting");
			}
			NoRotate noRotate = UnluckyClient.INSTANCE.modules.get(NoRotate.class);
			if (!noRotate.blockYaw.get() || !noRotate.blockPitch.get()
					|| !noRotate.acknowledgeCurrent.get() || !noRotate.onlyAlive.get()) {
				problems.add("NoRotate defaults drifted from position-preserving corrections");
			}
			boolean noRotateEnabled = noRotate.isEnabled();
			noRotate.setEnabledSilently(true);
			PositionMoveRotation correction = new PositionMoveRotation(mc.player.position(),
					Vec3.ZERO, mc.player.getYRot() + 30.0f, mc.player.getXRot() + 20.0f);
			ClientboundPlayerPositionPacket correctionPacket = new ClientboundPlayerPositionPacket(
					1, correction, Set.of());
			PositionMoveRotation filtered = noRotate.filter(correctionPacket, correction);
			if (!filtered.position().equals(correction.position())
					|| filtered.yRot() != mc.player.getYRot()
					|| filtered.xRot() != mc.player.getXRot()) {
				problems.add("NoRotate changed position or failed to isolate correction rotation");
			}
			noRotate.setEnabledSilently(noRotateEnabled);

			ProjectilePathUtil.ResultBuffer buffer = new ProjectilePathUtil.ResultBuffer();
			ProjectilePathUtil.ResultBuffer returned = ProjectilePathUtil.simulate(mc.level, mc.player,
					mc.player.getEyePosition(), new Vec3(0, -1, 0),
					ProjectilePathUtil.ProjectileType.ENDER_PEARL, 40, false, null, buffer);
			if (returned != buffer) {
				problems.add("projectile simulation discarded its reusable result buffer");
			}
			if (buffer.hit() == null || buffer.hit().getType() != HitResult.Type.BLOCK) {
				problems.add("downward projectile path did not collide with the scene floor");
			}

			if (pickedHostile instanceof Zombie zombie) {
				ProjectileAimSolver.Solution solution = ProjectileAimSolver.solve(
						new ProjectileAimSolver.Request(mc.level, mc.player,
								mc.player.getEyePosition(),
								ProjectilePathUtil.ProjectileType.BOW_ARROW, 20,
								zombie.getBoundingBox(), Vec3.ZERO, Vec3.ZERO, 100, false));
				if (!solution.valid() || solution.missDistance() > 1.0e-6) {
					problems.add("bow solver did not intersect the scene zombie");
				}
			}
			return problems;
		});

		if (!failures.isEmpty()) {
			throw new AssertionError("Shared targeting/projectile foundation failed: "
					+ String.join("; ", failures));
		}
		LOGGER.info("[foundations] targeting and projectile contracts hold");
	}

	private static ItemStack stackOf(String id) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
	}

	/**
	 * Puts something in front of the player for the render modules to find: a hostile and
	 * a passive mob (MobESP and the nametag paths branch on which), a dropped item
	 * (ItemPhysics), a container, an ore in the floor, a banner and a brewing stand.
	 *
	 * <p>Midnight, not noon, because a zombie at noon burns to death about a third of the
	 * way through the isolated pass and takes the hostile-mob coverage with it.
	 */
	private void buildScene(ClientGameTestContext context, TestServerContext server) {
		LOGGER.info("[modules] building the scene");
		String[] commands = {
				"gamemode creative @p",
				// explicit rather than inherited from the world-creation default: peaceful
				// would despawn the zombie and quietly halve the mob coverage
				"difficulty normal",
				"time set midnight",
				"execute at @p run summon minecraft:zombie ~3 ~ ~3",
				"execute at @p run summon minecraft:cow ~-3 ~ ~3",
				"execute at @p run summon minecraft:item ~1 ~1 ~2 {Item:{id:\"minecraft:diamond\",count:1}}",
				"execute at @p run setblock ~3 ~ ~-3 minecraft:chest",
				"execute at @p run setblock ~2 ~-1 ~2 minecraft:diamond_ore",
				"execute at @p run setblock ~-3 ~ ~3 minecraft:white_banner",
				"execute at @p run setblock ~-3 ~ ~-3 minecraft:brewing_stand",
				"give @p minecraft:diamond_sword",
				// worn, not held: ElytraPhysics drives the WingsLayer pose
				"item replace entity @p armor.chest with minecraft:elytra"};

		for (String command : commands) {
			server.runCommand(command);
		}
		context.waitTicks(DWELL);
		verifyScene(context);
	}

	/**
	 * Asserts the scene is really there.
	 *
	 * <p>{@code runCommand} goes through the command dispatcher, which reports a failure
	 * to the source and returns — a mistyped or version-changed command is silent. This
	 * turns that into the loud failure it should be, and names exactly what is missing.
	 */
	private void verifyScene(ClientGameTestContext context) {
		List<String> missing = context.computeOnClient(mc -> {
			List<String> gaps = new ArrayList<>();

			boolean zombie = false;
			boolean cow = false;
			boolean item = false;
			for (Entity entity : mc.level.entitiesForRendering()) {
				zombie |= entity instanceof Zombie;
				cow |= entity instanceof Cow;
				item |= entity instanceof ItemEntity;
			}
			if (!zombie) {
				gaps.add("zombie");
			}
			if (!cow) {
				gaps.add("cow");
			}
			if (!item) {
				gaps.add("dropped item");
			}

			// A box rather than the exact positions the commands used: `~` resolves against
			// the player's fractional position, so the floor of it is not ours to predict.
			BlockPos origin = mc.player.blockPosition();
			Set<Block> found = new HashSet<>();
			for (BlockPos pos : BlockPos.betweenClosed(
					origin.offset(-SCENE_RADIUS, -2, -SCENE_RADIUS),
					origin.offset(SCENE_RADIUS, 2, SCENE_RADIUS))) {
				found.add(mc.level.getBlockState(pos).getBlock());
			}
			for (Block block : SCENE_BLOCKS) {
				if (!found.contains(block)) {
					gaps.add(block.getName().getString());
				}
			}
			return gaps;
		});

		if (!missing.isEmpty()) {
			throw new AssertionError("Scene did not build — missing " + String.join(", ", missing)
					+ ". A command in buildScene failed silently; the render modules would then "
					+ "have been swept against an empty field.");
		}
		LOGGER.info("[modules] scene verified");
	}

	/** Every registered module except {@link #SKIPPED}, in registration order. */
	private List<Module> sweepList(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			List<Module> list = new ArrayList<>();
			for (Module module : UnluckyClient.INSTANCE.modules.all()) {
				if (!SKIPPED.containsKey(module.getClass())) {
					list.add(module);
				}
			}
			return list;
		});
	}

	/**
	 * Everything on at the title screen, where there is no level and no player.
	 *
	 * <p>A module is expected to do nothing here, and "does nothing" is the assertion: an
	 * unguarded {@code mc.player} or a registry read that is not ready yet throws instead.
	 * That second half is not hypothetical — 26.2 binds item components only once a world
	 * syncs its registries, and in one afternoon that trap took the client down twice, once
	 * from a module constructor and once from a block-entity probe behind a GUI tab. Both
	 * were found by tests; neither would have been found by this test as it stood, because
	 * every module pass ran inside a world.
	 *
	 * <p>All at once rather than one at a time: with no world there is nothing to isolate
	 * blame between, and a module that throws here does it on the first tick.
	 */
	private void everyModuleWithNoWorld(ClientGameTestContext context) {
		List<Module> sweep = sweepList(context);
		LOGGER.info("[modules] all {} at the title screen", sweep.size());
		Map<Module, Boolean> before = new LinkedHashMap<>();

		context.runOnClient(mc -> {
			for (Module module : sweep) {
				before.put(module, module.isEnabled());
				module.setEnabled(true);
			}
		});
		context.waitTicks(TOGETHER_DWELL);
		context.runOnClient(mc -> before.forEach(Module::setEnabled));
		context.waitTick();
	}

	/**
	 * One module at a time, each returned to the state it was found in. This is the pass
	 * that names the culprit: the last line in the log before the crash is the module.
	 */
	private void eachModuleAlone(ClientGameTestContext context, List<Module> sweep) {
		LOGGER.info("[modules] {} modules, one at a time ({} skipped)", sweep.size(), SKIPPED.size());
		SKIPPED.forEach((type, why) -> LOGGER.info("[modules] skipping {} — {}", type.getSimpleName(), why));

		for (Module module : sweep) {
			LOGGER.info("[modules] {} — {}", module.getCategory().displayName(), module.getName());
			boolean was = context.computeOnClient(mc -> module.isEnabled());
			// setEnabled, not setEnabledSilently: the toast it fires is itself a render
			// path, and one the HUD's toast avoidance reads every frame.
			context.runOnClient(mc -> module.setEnabled(true));
			context.waitTicks(DWELL);
			context.runOnClient(mc -> module.setEnabled(was));
			context.waitTick();
		}
	}

	/** Everything on at once — the frame no single-module pass can produce. */
	private void everyModuleAtOnce(ClientGameTestContext context, List<Module> sweep) {
		LOGGER.info("[modules] all {} at once", sweep.size());
		Map<Module, Boolean> before = new LinkedHashMap<>();

		context.runOnClient(mc -> {
			for (Module module : sweep) {
				before.put(module, module.isEnabled());
				module.setEnabled(true);
			}
		});
		context.waitTicks(TOGETHER_DWELL);
		context.runOnClient(mc -> before.forEach(Module::setEnabled));
		context.waitTick();
	}
}
