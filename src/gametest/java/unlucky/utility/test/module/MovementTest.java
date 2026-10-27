package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.movement.ElytraRecast;
import unlucky.utility.client.module.modules.movement.NoPush;
import unlucky.utility.client.module.modules.movement.Parkour;
import unlucky.utility.client.module.modules.movement.ReverseStep;
import unlucky.utility.client.module.modules.movement.SafeWalk;
import unlucky.utility.client.module.modules.movement.Step;
import unlucky.utility.client.module.modules.player.AutoArmor;
import unlucky.utility.client.module.modules.player.ElytraSwap;
import unlucky.utility.client.util.InventoryActionCoordinator;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;

/**
 * Step, push, edge and elytra-priority contracts for the movement modules.
 */
public final class MovementTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(ElytraRecast.class, ElytraSwap.class, NoPush.class, Parkour.class, ReverseStep.class, SafeWalk.class, Step.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyMovementContracts(context);
	}

	/**
	 * The reactive movement modules, checked against the two things that are invisible in a sweep
	 * that only asks "did it throw".
	 *
	 * <p><b>Off has to mean off.</b> Every one of these joins a hook something else already owns —
	 * Step the step-height getter, NoPush the collision push, SafeWalk the edge decision Scaffold
	 * answers — so a disabled module is not merely idle, it is a module still being <em>asked</em>
	 * every tick and expected to say nothing. That failure does not throw and does not show up in a
	 * screenshot; it shows up as a player who cannot walk off a ledge with the module switched off.
	 *
	 * <p><b>And the priorities are a contract between modules, not a constant.</b> ElytraSwap
	 * outranking AutoArmor and not the totem is the whole reason a failing elytra gets replaced
	 * instead of covered over; nothing at runtime would notice if that ordering were edited.
	 */
	private void verifyMovementContracts(ClientGameTestContext context) {
		List<String> failures = context.computeOnClient(mc -> {
			List<String> problems = new ArrayList<>();

			// Step: raises the getter while on, restores it exactly on disable. Read through
			// maxUpStep() rather than the module's own method so this covers the mixin too.
			Step step = UnluckyClient.INSTANCE.modules.get(Step.class);
			boolean wasStep = step.isEnabled();
			double previousHeight = step.height.get();
			step.setEnabledSilently(false);
			float vanillaStep = mc.player.maxUpStep();
			step.setEnabledSilently(true);
			step.height.set(2.0);
			if (mc.player.maxUpStep() < 1.99f) {
				problems.add("Step no longer raises the vanilla step height");
			}
			step.setEnabledSilently(false);
			if (mc.player.maxUpStep() != vanillaStep) {
				problems.add("Step left the step height raised after disable");
			}
			step.height.set(previousHeight);
			step.setEnabledSilently(wasStep);

			// SafeWalk: transparent when off. Scaffold's three answers must come back unchanged,
			// or the shared hook has quietly acquired a second policy.
			SafeWalk safeWalk = UnluckyClient.INSTANCE.modules.get(SafeWalk.class);
			boolean wasSafeWalk = safeWalk.isEnabled();
			safeWalk.setEnabledSilently(false);
			for (int scaffoldAnswer = -1; scaffoldAnswer <= 1; scaffoldAnswer++) {
				if (safeWalk.edgePolicy(mc.player, false, scaffoldAnswer) != scaffoldAnswer) {
					problems.add("a disabled SafeWalk changed Scaffold's edge answer "
							+ scaffoldAnswer);
				}
			}
			if (safeWalk.isServerObservableNow()) {
				problems.add("a disabled SafeWalk still reports itself observable");
			}

			// The precedence table, both ways round. Row {own, scaffold, scaffoldWins, safeWalkWins}.
			// The load-bearing row is {1, 0, ...}: Scaffold Descend saying "walk off" while SafeWalk
			// says "clamp" is what happens every time somebody builds downwards with both on.
			String previousPrecedence = safeWalk.precedence.get();
			int[][] table = {
					{-1, -1, -1, -1},
					{-1, 0, 0, 0},
					{-1, 1, 1, 1},
					{1, -1, 1, 1},
					{1, 0, 0, 1},
					{1, 1, 1, 1}};
			for (int[] row : table) {
				safeWalk.precedence.set("Scaffold");
				if (safeWalk.reconcile(row[0], row[1]) != row[2]) {
					problems.add("SafeWalk/Scaffold precedence changed for own=" + row[0]
							+ " scaffold=" + row[1] + " under Scaffold precedence");
				}
				safeWalk.precedence.set("SafeWalk");
				if (safeWalk.reconcile(row[0], row[1]) != row[3]) {
					problems.add("SafeWalk/Scaffold precedence changed for own=" + row[0]
							+ " scaffold=" + row[1] + " under SafeWalk precedence");
				}
			}
			safeWalk.precedence.set(previousPrecedence);
			safeWalk.setEnabledSilently(wasSafeWalk);

			// NoPush: the source classification, and that it only ever answers for us.
			NoPush noPush = UnluckyClient.INSTANCE.modules.get(NoPush.class);
			boolean wasNoPush = noPush.isEnabled();
			Entity pusher = null;
			for (Entity entity : mc.level.entitiesForRendering()) {
				if (entity instanceof Zombie) {
					pusher = entity;
				}
			}
			if (pusher != null) {
				Vec3 shove = new Vec3(0.4, 0.0, 0.4);
				noPush.setEnabledSilently(true);
				if (noPush.entityPush(pusher, mc.player, shove).horizontalDistance() != 0.0) {
					problems.add("NoPush no longer stops a mob push");
				}
				if (!noPush.entityPush(pusher, pusher, shove).equals(shove)) {
					problems.add("NoPush interfered with a push aimed at somebody else");
				}
				noPush.setEnabledSilently(false);
				if (!noPush.entityPush(pusher, mc.player, shove).equals(shove)) {
					problems.add("a disabled NoPush still suppressed a push");
				}
				noPush.setEnabledSilently(wasNoPush);
			}

			// The reactive modules are CONDITIONAL, and a player standing still on a superflat is
			// the definition of "nothing to react to".
			ReverseStep reverseStep = UnluckyClient.INSTANCE.modules.get(ReverseStep.class);
			Parkour parkour = UnluckyClient.INSTANCE.modules.get(Parkour.class);
			ElytraRecast recast = UnluckyClient.INSTANCE.modules.get(ElytraRecast.class);
			for (Module module : List.of(reverseStep, parkour, recast, safeWalk)) {
				boolean was = module.isEnabled();
				module.setEnabledSilently(true);
				if (module.isServerObservableNow()) {
					problems.add(module.getName() + " reports itself observable while idle");
				}
				module.setEnabledSilently(was);
			}

			// ElytraSwap's place in the click queue, which nothing at runtime would miss.
			if (InventoryActionCoordinator.PRIORITY_ELYTRA_SAFETY
					<= InventoryActionCoordinator.PRIORITY_EQUIPMENT
					|| InventoryActionCoordinator.PRIORITY_ELYTRA_SAFETY
							>= InventoryActionCoordinator.PRIORITY_TOTEM) {
				problems.add("elytra safety no longer sits between armour upkeep and the totem");
			}
			ElytraSwap elytraSwap = UnluckyClient.INSTANCE.modules.get(ElytraSwap.class);
			boolean wasSwap = elytraSwap.isEnabled();
			elytraSwap.setEnabledSilently(false);
			if (elytraSwap.guardsChestSlot()) {
				problems.add("a disabled ElytraSwap still holds AutoArmor off the chest slot");
			}
			elytraSwap.setEnabledSilently(wasSwap);
			return problems;
		});

		if (!failures.isEmpty()) {
			throw new AssertionError("Movement/equipment contracts failed: "
					+ String.join("; ", failures));
		}
		LOGGER.info("[movement] step, push, edge and elytra-priority contracts hold");
	}
}
