package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.combat.AutoTotem;
import unlucky.utility.client.module.modules.combat.BlatantMaceKill;
import unlucky.utility.client.module.modules.combat.Criticals;
import unlucky.utility.client.module.modules.combat.ElytraMace;
import unlucky.utility.client.module.modules.combat.LegitMaceKill;
import unlucky.utility.client.module.modules.combat.MaceCombo;
import unlucky.utility.client.module.modules.movement.ElytraFly;
import unlucky.utility.client.module.modules.movement.ElytraRecast;
import unlucky.utility.client.module.modules.player.AutoArmor;
import unlucky.utility.client.module.modules.player.ElytraSwap;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;
import static unlucky.utility.test.module.ModuleTestSupport.summonAt;
import static unlucky.utility.test.module.ModuleTestSupport.attack;
import static unlucky.utility.test.module.ModuleTestSupport.serverHealth;
import static unlucky.utility.test.module.ModuleTestSupport.damageTaken;

/**
 * ElytraMace dives from offhand and inventory elytra, smashes, and recovers.
 */
public final class ElytraMaceTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(ElytraMace.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyElytraMace(context, server);
	}

	/**
	 * ElytraMace, judged by the server: a real dive onto an armoured zombie lands a smash worth
	 * far more than a plain hit, costs the player no fall damage and ends with the wings back on;
	 * and a target the glide will never reach does not cost the player their wings.
	 *
	 * <p>The test flies the way F1 expects the player to — re-aiming at the zombie every tick,
	 * pulling up once the hit is in — and nothing else. The dive is steep, so it banks thirty-odd
	 * blocks of fall and a miss would kill; the wind charges in the hotbar are what let the planner
	 * commit to it at all, which is the rescue rule being exercised as much as the strike.
	 *
	 * <p>Well away from the scene: the planner picks the hostile nearest the crosshair, and the
	 * scene's own zombie must never be the one it dives on.
	 */
	private void verifyElytraMace(ClientGameTestContext context, TestServerContext server) {
		ElytraMace elytraMace = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.get(ElytraMace.class));
		// every module that touches the offhand, the chest slot, the glide or the attack hook
		List<Module> quiet = context.computeOnClient(mc -> List.of(
				UnluckyClient.INSTANCE.modules.get(AutoTotem.class),
				UnluckyClient.INSTANCE.modules.get(AutoArmor.class),
				UnluckyClient.INSTANCE.modules.get(ElytraSwap.class),
				UnluckyClient.INSTANCE.modules.get(ElytraRecast.class),
				UnluckyClient.INSTANCE.modules.get(ElytraFly.class),
				UnluckyClient.INSTANCE.modules.get(Criticals.class),
				UnluckyClient.INSTANCE.modules.get(LegitMaceKill.class),
				UnluckyClient.INSTANCE.modules.get(BlatantMaceKill.class),
				UnluckyClient.INSTANCE.modules.get(MaceCombo.class)));
		Map<Module, Boolean> quietWas = new LinkedHashMap<>();
		boolean wasEnabled = elytraMace.isEnabled();
		boolean wasHostiles = elytraMace.hostiles.get();
		String wasSource = elytraMace.chestplateSource.get();
		Vec3 home = context.computeOnClient(mc -> mc.player.position());
		List<String> problems = new ArrayList<>();

		context.runOnClient(mc -> {
			for (Module module : quiet) {
				quietWas.put(module, module.isEnabled());
				module.setEnabledSilently(false);
			}
		});
		server.runCommand("gamemode survival @p");
		server.runCommand("effect clear @p");
		server.runCommand("execute at @p run tp @p ~80 ~ ~");
		context.waitTicks(20);
		server.runCommand("clear @p");
		server.runCommand("item replace entity @p armor.chest with minecraft:elytra");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:netherite_chestplate");
		server.runCommand("item replace entity @p hotbar.0 with minecraft:mace");
		server.runCommand("item replace entity @p hotbar.1 with minecraft:firework_rocket 16");
		server.runCommand("item replace entity @p hotbar.2 with minecraft:wind_charge 16");
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		try {
			int zombie = summonAt(context, server, "unlucky_elytra_mace", "~3 ~ ~",
					",equipment:{head:{id:\"minecraft:netherite_helmet\",count:1},"
							+ "chest:{id:\"minecraft:netherite_chestplate\",count:1},"
							+ "legs:{id:\"minecraft:netherite_leggings\",count:1},"
							+ "feet:{id:\"minecraft:netherite_boots\",count:1}},"
							+ "attributes:[{id:\"minecraft:max_health\",base:" + ELYTRA_MACE_HEALTH + "d}],"
							+ "Health:" + ELYTRA_MACE_HEALTH + "f");
			// the mace only just went into the hand: wait out a full charge for the plain hit
			context.waitTicks(40);
			attack(context, zombie);
			context.waitTicks(5);
			float plain = ELYTRA_MACE_HEALTH - serverHealth(server, zombie);
			healElytraMaceTarget(server);

			// Offhand, the default: a right-click swap, so the server's attack charge resets and the
			// smash lands without its crit — still many times a plain hit.
			context.runOnClient(mc -> elytraMace.hostiles.set(true));
			Dive offhand = dive(context, server, elytraMace, zombie, "Offhand", problems);
			if (plain <= 0.0f) {
				problems.add("the plain mace hit dealt no damage, so the smash comparison means nothing");
			} else if (offhand.dealt() < plain * 3.0f) {
				problems.add("the Offhand dive did not smash (plain " + plain + ", dive " + offhand.dealt() + ")");
			}

			// Inventory: a container click leaves the mace's charge alone, so the same dive crits. This is
			// the measurement behind the setting's description, not a feature being asserted for its own sake.
			server.runCommand("item replace entity @p weapon.offhand with minecraft:air");
			server.runCommand("item replace entity @p inventory.0 with minecraft:netherite_chestplate");
			Dive inventory = dive(context, server, elytraMace, zombie, "Inventory", problems);
			if (inventory.dealt() < offhand.dealt() * 1.3f) {
				problems.add("the Inventory dive did not crit (Offhand " + offhand.dealt() + ", Inventory "
						+ inventory.dealt() + ")");
			}

			// Off the line: a glide that passes ten blocks to the side of a zombie it has in view
			// has to keep its wings the whole way.
			context.runOnClient(mc -> {
				elytraMace.setEnabledSilently(false);
				elytraMace.chestplateSource.set("Offhand");
			});
			server.runCommand("execute at @e[tag=unlucky_elytra_mace,limit=1] run tp @p ~-20 ~30 ~-10 -90 10");
			healElytraMaceTarget(server);
			server.runCommand("item replace entity @p armor.chest with minecraft:elytra");
			server.runCommand("item replace entity @p weapon.offhand with minecraft:netherite_chestplate");
			context.waitTicks(3);
			int damageBefore = damageTaken(server);
			context.runOnClient(mc -> {
				elytraMace.setEnabledSilently(true);
				startTestGlide(mc.player);
			});
			boolean selected = false;
			boolean swapped = false;
			boolean fell = false;
			for (int tick = 0; tick < 40; tick++) {
				context.runOnClient(mc -> {
					mc.player.setYRot(-90.0f);
					mc.player.setXRot(10.0f);
				});
				context.waitTicks(1);
				ElytraMace.State now = context.computeOnClient(mc -> elytraMace.state());
				selected |= now == ElytraMace.State.ARMED
						&& context.computeOnClient(mc -> elytraMace.target() != null);
				swapped |= now.compareTo(ElytraMace.State.UNGLIDE) >= 0 || !context.computeOnClient(
						mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
				fell |= !context.computeOnClient(mc -> mc.player.isFallFlying());
			}
			if (!selected) {
				problems.add("the off-line zombie was never picked as a target, so the check below means nothing");
			}
			if (swapped) {
				problems.add("ElytraMace took the wings off for a target the glide could not reach");
			}
			if (fell) {
				problems.add("the glide past an unreachable target stopped");
			}
			if (serverHealth(server, zombie) < ELYTRA_MACE_HEALTH || damageTaken(server) != damageBefore) {
				problems.add("the off-line pass hit the zombie or hurt the player");
			}
			if (!context.computeOnClient(mc -> mc.getConnection() != null && mc.level != null)) {
				problems.add("ElytraMace got the player disconnected");
			}
		} finally {
			context.runOnClient(mc -> {
				elytraMace.setEnabledSilently(wasEnabled);
				elytraMace.hostiles.set(wasHostiles);
				elytraMace.chestplateSource.set(wasSource);
				quietWas.forEach(Module::setEnabledSilently);
			});
			server.runCommand("kill @e[tag=unlucky_elytra_mace]");
			server.runCommand(String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f", home.x, home.y, home.z));
			server.runCommand("clear @p");
			server.runCommand("item replace entity @p armor.chest with minecraft:elytra");
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
			server.runCommand("gamemode creative @p");
			context.waitTicks(25);
		}

		if (!problems.isEmpty()) {
			throw new AssertionError("ElytraMace contracts failed: " + String.join("; ", problems));
		}
		LOGGER.info("[elytramace] the dive smashed and recovered, and the off-line glide kept its wings");
	}

	/** What one dive did: to the zombie, and whether the planner, the strike and the rocket all happened. */
	private record Dive(float dealt, boolean struck) {
	}

	/**
	 * One dive from forty up and twenty-three back — about sixty degrees down onto the zombie —
	 * with the given chestplate source. Every contract that holds for any source is checked here:
	 * a commit, a strike, no fall damage, the wings back on, and one rocket the server accepted,
	 * which it only does for a glide it agrees is happening.
	 */
	private static Dive dive(ClientGameTestContext context, TestServerContext server, ElytraMace elytraMace,
			int zombie, String source, List<String> problems) {
		healElytraMaceTarget(server);
		server.runCommand("execute at @e[tag=unlucky_elytra_mace,limit=1] run tp @p ~-23 ~40 ~ -90 60");
		context.waitTicks(2);
		context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO)); // a teleport keeps the velocity
		context.waitTicks(1);
		int damageBefore = damageTaken(server);
		int rocketsBefore = rocketsLeft(server);
		context.runOnClient(mc -> {
			elytraMace.chestplateSource.set(source);
			elytraMace.setEnabledSilently(true);
			startTestGlide(mc.player);
		});
		boolean committed = false;
		boolean struck = false;
		boolean missed = false;
		int ticks = 0;
		for (; ticks < 240; ticks++) {
			// the player's half of F1: track the zombie, and pull up once the hit is in
			boolean pullUp = struck;
			context.runOnClient(mc -> {
				if (pullUp) {
					mc.player.setYRot(-90.0f);
					mc.player.setXRot(-30.0f);
				} else if (mc.level.getEntity(zombie) instanceof LivingEntity target) {
					aimAt(mc.player, target.getBoundingBox().getCenter());
				}
			});
			context.waitTicks(1);
			ElytraMace.State state = context.computeOnClient(mc -> elytraMace.state());
			committed |= state.compareTo(ElytraMace.State.UNGLIDE) >= 0;
			missed |= state == ElytraMace.State.RESCUE;
			if (state == ElytraMace.State.RECOVER) {
				struck = true;
			} else if (struck && state.compareTo(ElytraMace.State.ARMED) <= 0) {
				break; // recovery finished
			}
			if (!struck && context.computeOnClient(mc -> mc.player.onGround())) {
				break; // landed without a strike; the checks below say why
			}
		}
		context.waitTicks(5);
		float dealt = ELYTRA_MACE_HEALTH - serverHealth(server, zombie);
		LOGGER.info("[elytramace] {} dive: committed={} struck={} missed={} after {} ticks, dealt {}",
				source, committed, struck, missed, ticks, dealt);
		if (!committed) {
			problems.add(source + ": ElytraMace never committed to a dive straight onto its target");
		} else if (!struck) {
			problems.add(source + ": ElytraMace committed but never struck ("
					+ context.computeOnClient(mc -> elytraMace.debug()) + ")");
		}
		if (damageTaken(server) != damageBefore) {
			problems.add(source + ": the dive cost the player " + (damageTaken(server) - damageBefore) / 10.0f
					+ " health in fall damage");
		}
		if (!context.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))
				|| !server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0)
						.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))) {
			problems.add(source + ": the elytra was not back on after the recovery");
		}
		if (struck && rocketsLeft(server) != rocketsBefore - 1) {
			problems.add(source + ": the recovery did not fire exactly one rocket into a confirmed glide ("
					+ rocketsBefore + " before, " + rocketsLeft(server) + " after)");
		}
		// Back on the ground until the rocket burns out, so the next dive starts from rest rather
		// than from this one's boost.
		context.runOnClient(mc -> elytraMace.setEnabledSilently(false));
		server.runCommand("kill @e[type=minecraft:firework_rocket]");
		server.runCommand("execute at @e[tag=unlucky_elytra_mace,limit=1] run tp @p ~-6 ~ ~");
		context.waitTicks(2);
		context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
		context.waitFor(mc -> mc.player.onGround() && !mc.player.isFallFlying(), 40);
		return new Dive(dealt, struck);
	}

	private static int rocketsLeft(TestServerContext server) {
		return server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getInventory().getItem(1).getCount());
	}

	/** Max health of ElytraMace's target: enough that a smash is measured rather than merely fatal. */
	private static final float ELYTRA_MACE_HEALTH = 500.0f;

	private static void healElytraMaceTarget(TestServerContext server) {
		server.runCommand("data merge entity @e[tag=unlucky_elytra_mace,limit=1] {Health:" + ELYTRA_MACE_HEALTH + "f}");
	}

	/** What a jump press does mid-air: the local flag, and the command that tells the server. */
	private static void startTestGlide(LocalPlayer player) {
		if (player.tryToStartFallFlying()) {
			player.connection.send(new ServerboundPlayerCommandPacket(player,
					ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		}
	}

	/**
	 * Aims from where the eye will be when the coming tick ends, which is where ElytraMace asks
	 * whether the crosshair is on the box. Aiming from the current eye leaves a fast dive a block
	 * and a half behind its own crosshair at the one range that matters.
	 */
	private static void aimAt(LocalPlayer player, Vec3 aim) {
		Vec3 eye = player.getEyePosition().add(player.getDeltaMovement());
		player.setYRot((float) Math.toDegrees(Math.atan2(aim.z - eye.z, aim.x - eye.x)) - 90.0f);
		player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y - eye.y,
				Math.hypot(aim.x - eye.x, aim.z - eye.z))));
	}
}
