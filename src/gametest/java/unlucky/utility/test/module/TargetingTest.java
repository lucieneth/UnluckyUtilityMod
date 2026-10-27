package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.movement.AntiVoid;
import unlucky.utility.client.module.modules.player.ChestStealer;
import unlucky.utility.client.module.modules.player.NoRotate;
import unlucky.utility.client.module.modules.render.Trajectories;
import unlucky.utility.client.module.modules.world.NewChunks;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.PacketQueueManager;
import unlucky.utility.client.util.ProjectileAimSolver;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.TargetingUtil;
import unlucky.utility.client.util.WeatherOverrideManager;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;

/**
 * Target selection and projectile paths against the real scene: what the combat modules aim with.
 */
public final class TargetingTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(AntiVoid.class, ChestStealer.class, NoRotate.class);
	}

	@Override
	public Category category() {
		return Category.COMBAT;
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyTargetingAndProjectiles(context);
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
			// A fresh instance, not the registered one: this asserts what the class *declares*,
			// and the live module carries whatever the user's config — or the shipped baseline
			// in assets/unlucky/default_config.json — has set on top of that. Reading the live
			// values would turn any deliberate default in the baseline into a test failure.
			Trajectories trajectories = new Trajectories();
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
			ServerboundPunchPacket swing = ServerboundPunchPacket.INSTANCE;
			ServerboundAcceptTeleportationPacket teleportConfirm =
					new ServerboundAcceptTeleportationPacket(1, 0, 0, 0, 0, 0);
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
}
