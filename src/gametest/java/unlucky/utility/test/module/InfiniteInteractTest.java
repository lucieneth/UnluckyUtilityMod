package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.player.InfiniteInteract;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;
import static unlucky.utility.test.module.ModuleTestSupport.serverDrift;
import static unlucky.utility.test.module.ModuleTestSupport.summonAt;
import static unlucky.utility.test.module.ModuleTestSupport.attack;
import static unlucky.utility.test.module.ModuleTestSupport.serverHealth;

/**
 * InfiniteInteract: one step out, the action lands, and the server brings the player back.
 */
public final class InfiniteInteractTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(InfiniteInteract.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyInfiniteInteract(context, server);
	}

	/**
	 * InfiniteInteract's single step, judged by the server: a zombie and a block eight blocks
	 * off are hit from the step, the server ends up back beside the client once the actions
	 * stop, and a target past one step is left to vanilla without a packet.
	 *
	 * <p>The last one matters as much as the first two. A step the server rejects, or one
	 * that never comes back, leaves it disagreeing with the client about where you stand —
	 * and on 26.3 a second step in the same tick is a kick, not a clamp.
	 */
	private void verifyInfiniteInteract(ClientGameTestContext context, TestServerContext server) {
		InfiniteInteract infinite = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class));
		boolean wasInfinite = infinite.isEnabled();
		List<String> problems = new ArrayList<>();
		// outside the scene, which sits within three blocks
		server.runCommand("execute at @p run setblock ~-8 ~ ~ minecraft:stone");
		BlockPos stone = context.computeOnClient(mc -> mc.player.blockPosition().offset(-8, 0, 0));
		int far = summonAt(context, server, "unlucky_infinite_far", "~ ~ ~8");
		int tooFar = summonAt(context, server, "unlucky_infinite_too_far", "~ ~ ~20");
		context.waitTicks(20);
		try {
			context.runOnClient(mc -> infinite.setEnabledSilently(true));
			attack(context, far);
			context.waitTicks(5);
			if (serverHealth(server, far) >= 20.0f) {
				problems.add("InfiniteInteract's step did not land a hit eight blocks off");
			}
			float drift = serverDrift(context, server);
			if (drift > 0.1f) {
				problems.add("the server was left " + drift + " blocks from the client after an attack");
			}

			// a sword breaks nothing in creative, on either side
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_pickaxe");
			context.waitTicks(2);
			context.runOnClient(mc -> mc.gameMode.startDestroyBlock(stone, net.minecraft.core.Direction.UP));
			context.waitTicks(5);
			if (!server.computeOnServer(s -> s.overworld().getBlockState(stone).isAir())) {
				problems.add("InfiniteInteract's step did not break a block eight blocks off");
			}
			drift = serverDrift(context, server);
			if (drift > 0.1f) {
				problems.add("the server was left " + drift + " blocks from the client after a break");
			}

			// Survival: a break is a start, a run of progress ticks and a stop, and every one of
			// them has to come from the step. Held the way a player holds it — crosshair on the
			// block, attack key down — so vanilla's own per-tick break loop is the one driving.
			server.runCommand("gamemode survival @p");
			server.runCommand("execute at @p run setblock ~-8 ~ ~ minecraft:stone");
			context.waitTicks(20);
			context.runOnClient(mc -> {
				Vec3 eye = mc.player.getEyePosition();
				Vec3 aim = Vec3.atCenterOf(stone);
				mc.player.setYRot((float) Math.toDegrees(Math.atan2(aim.z - eye.z, aim.x - eye.x)) - 90.0f);
				mc.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y - eye.y,
						Math.hypot(aim.x - eye.x, aim.z - eye.z))));
				mc.options.keyAttack.setDown(true);
			});
			int breakTicks;
			try {
				breakTicks = context.waitFor(mc -> mc.level.getBlockState(stone).isAir(), 60);
			} finally {
				context.runOnClient(mc -> mc.options.keyAttack.setDown(false));
			}
			context.waitTicks(5);
			if (!server.computeOnServer(s -> s.overworld().getBlockState(stone).isAir())) {
				problems.add("a survival break through InfiniteInteract did not reach the server");
			}
			drift = serverDrift(context, server);
			if (drift > 0.1f) {
				problems.add("the server was left " + drift + " blocks from the client after a survival break");
			}
			server.runCommand("gamemode creative @p");
			LOGGER.info("[infinite] survival break from the step took {} ticks", breakTicks);

			attack(context, tooFar);
			context.waitTicks(5);
			if (serverHealth(server, tooFar) < 20.0f) {
				problems.add("a target past one packet step was still hit");
			}
			if (!context.computeOnClient(mc -> mc.getConnection() != null && mc.level != null)) {
				problems.add("InfiniteInteract got the player disconnected");
			}
		} finally {
			context.runOnClient(mc -> infinite.setEnabledSilently(wasInfinite));
			server.runCommand("kill @e[tag=unlucky_infinite_far]");
			server.runCommand("kill @e[tag=unlucky_infinite_too_far]");
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
			context.waitTicks(25);
		}

		if (!problems.isEmpty()) {
			throw new AssertionError("InfiniteInteract contracts failed: " + String.join("; ", problems));
		}
		LOGGER.info("[infinite] one step out, the action lands, and the server comes back");
	}
}
