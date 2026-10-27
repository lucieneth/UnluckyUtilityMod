package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.entity.Entity;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.combat.BlatantMaceKill;
import unlucky.utility.client.module.modules.combat.Criticals;
import unlucky.utility.client.util.MaceKillPackets;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;
import static unlucky.utility.test.module.ModuleTestSupport.summonHeldTarget;
import static unlucky.utility.test.module.ModuleTestSupport.attack;
import static unlucky.utility.test.module.ModuleTestSupport.serverHealth;
import static unlucky.utility.test.module.ModuleTestSupport.damageTaken;

/**
 * Criticals and BlatantMaceKill, judged by the server's damage numbers.
 *
 * <p>Kills mobs and flips the game mode, so it runs after the tests that read the fresh scene.
 */
public final class HeldAttacksTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(Criticals.class, BlatantMaceKill.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyHeldAttacks(context, server);
	}

	/**
	 * The attacks that need the server to watch a fall first, judged by the server.
	 *
	 * <p>26.3 takes one position per client tick and kicks for a second, so Criticals' hop
	 * and the mace spoof became sequences that hold the attack across ticks. Everything
	 * that can go wrong with that renders perfectly: the hit lands plain, the smash never
	 * banks, or an abandoned climb comes back down as a lethal fall. Singleplayer runs the
	 * same {@code handleMovePlayer}, so the integrated server is a fair judge of all three —
	 * the damage it dealt, and the damage it took.
	 *
	 * <p>Survival, so a banked fall is real damage, and every zombie held still, so any
	 * damage the player takes is ours. Damage is read from the server's own
	 * {@code damage_taken} statistic rather than from health, which regenerates.
	 */
	private void verifyHeldAttacks(ClientGameTestContext context, TestServerContext server) {
		Criticals criticals = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.get(Criticals.class));
		BlatantMaceKill mace = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.get(BlatantMaceKill.class));
		boolean wasCriticals = criticals.isEnabled();
		boolean wasMace = mace.isEnabled();
		String previousMode = criticals.mode.get();
		List<String> problems = new ArrayList<>();

		server.runCommand("gamemode survival @p");
		server.runCommand("execute as @e[type=minecraft:zombie] run data merge entity @s {NoAI:1b}");
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
		try {
			// Criticals, Packet: the same zombie hit plain and then with the hop, so the
			// ratio is the crit multiplier and nothing else.
			int crit = summonHeldTarget(context, server, "unlucky_held_crit");
			context.waitTicks(30);
			attack(context, crit);
			context.waitTicks(5);
			float plain = 20.0f - serverHealth(server, crit);
			server.runCommand("data merge entity @e[tag=unlucky_held_crit,limit=1] {Health:20f}");
			context.waitTicks(30);
			context.runOnClient(mc -> {
				criticals.mode.set("Packet");
				criticals.setEnabledSilently(true);
			});
			attack(context, crit);
			context.waitTicks(5);
			float critical = 20.0f - serverHealth(server, crit);
			context.runOnClient(mc -> criticals.setEnabledSilently(false));
			if (plain <= 0.0f) {
				problems.add("the plain sword hit dealt no damage, so the crit comparison means nothing");
			} else if (critical < plain * 1.3f) {
				problems.add("Criticals Packet did not crit (plain " + plain + ", with the hop " + critical + ")");
			}

			// BlatantMaceKill: the smash lands, and the fall it banked is never taken.
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:mace");
			int smashed = summonHeldTarget(context, server, "unlucky_held_mace");
			context.waitTicks(40);
			int damageBefore = damageTaken(server);
			context.runOnClient(mc -> mace.setEnabledSilently(true));
			attack(context, smashed);
			context.waitFor(mc -> !MaceKillPackets.isBusy(), 40);
			context.waitTicks(5);
			if (serverHealth(server, smashed) > 0.0f) {
				problems.add("BlatantMaceKill's smash did not kill a zombie (health "
						+ serverHealth(server, smashed) + ")");
			}
			if (damageTaken(server) != damageBefore) {
				problems.add("BlatantMaceKill landed its banked fall on the player");
			}

			// Abandoned after the climb: the target vanishes before the hit can go, and the
			// way back down must not land a single point of the fall.
			int abandoned = summonHeldTarget(context, server, "unlucky_held_abort");
			context.waitTicks(40);
			damageBefore = damageTaken(server);
			context.runOnClient(mc -> {
				Entity entity = mc.level.getEntity(abandoned);
				mc.gameMode.attack(mc.player, entity);
				// client-side only: the server still has it, so a hit that went out would land
				mc.level.removeEntity(abandoned, Entity.RemovalReason.DISCARDED);
			});
			int stepTicks = context.waitFor(mc -> !MaceKillPackets.isBusy(), 200);
			context.waitTicks(5);
			if (serverHealth(server, abandoned) < 20.0f) {
				problems.add("an abandoned mace spoof still hit its target");
			}
			if (damageTaken(server) != damageBefore) {
				problems.add("an abandoned mace spoof landed its fall on the player");
			}
			LOGGER.info("[held] abandoned climb stepped back down in {} ticks", stepTicks);

			if (!context.computeOnClient(mc -> mc.getConnection() != null && mc.level != null)) {
				problems.add("the held-attack sequences got the player disconnected");
			}
		} finally {
			context.runOnClient(mc -> {
				criticals.mode.set(previousMode);
				criticals.setEnabledSilently(wasCriticals);
				mace.setEnabledSilently(wasMace);
			});
			server.runCommand("kill @e[tag=unlucky_held_crit]");
			server.runCommand("kill @e[tag=unlucky_held_mace]");
			server.runCommand("kill @e[tag=unlucky_held_abort]");
			server.runCommand("execute as @e[type=minecraft:zombie] run data merge entity @s {NoAI:0b}");
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
			server.runCommand("gamemode creative @p");
			// a killed zombie lingers client-side for its death animation, and would be the
			// first hostile anything after this finds
			context.waitTicks(25);
		}

		if (!problems.isEmpty()) {
			throw new AssertionError("Held-attack contracts failed: " + String.join("; ", problems));
		}
		LOGGER.info("[held] packet crit, mace smash and abandoned climb all judged by the server");
	}
}
