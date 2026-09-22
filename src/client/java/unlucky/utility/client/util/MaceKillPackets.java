package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Shared movement-packet sequence for the two mace amplification modules.
 *
 * <p>The server banks a smash from a fall it watched: up to a raised position, back down,
 * then the hit. Up to 26.2 all of that went out inside the attack call. 26.3 takes one
 * position per client tick ({@link MovePacketLimiter}), so it is a sequence now, one
 * window per step, with the attack held ({@link HeldAttack}) until the fall is banked:
 * <ol>
 *   <li>climb — sent from the attack itself, which is swallowed;</li>
 *   <li>descend and hit — the fall is banked, and the held attack goes out behind it;</li>
 *   <li>land — the server is put back exactly where the client is.</li>
 * </ol>
 * The client entity is never moved; only the server's idea of where we are.
 *
 * <p><b>Abandoning after the climb must not land the fall.</b> Going up banks nothing,
 * but coming down banks all of it, and the only thing that clears it without a landing
 * is the smash itself. So when the hit cannot happen — the target died in the tick
 * between, the module was switched off — the sequence steps back down in
 * {@link #SAFE_STEP} drops, each one landed, none long enough to hurt. A lethal spoof
 * height takes a few seconds to walk back down; that is the price of not dying to it.
 *
 * <p>Ticked from {@code UnluckyClient.tick()} rather than from either module, so that
 * turning the module off mid-sequence still finishes it.
 */
public final class MaceKillPackets {
	/** A landed drop shorter than the safe fall distance (3) does no damage. */
	private static final double SAFE_STEP = 2.5;

	private enum Stage {
		IDLE, CLIMBED, LANDING, STEPPING_DOWN
	}

	private static Stage stage = Stage.IDLE;
	private static Object owner;
	private static Entity target;
	private static double height;
	private static boolean resetFall;
	/** The window the last step went out in; the next step needs a later one. */
	private static long window;
	/** Where the server believes our feet are while stepping down. */
	private static double serverY;

	private MaceKillPackets() {
	}

	/**
	 * Starts a smash: the climb goes out now and the attack is held. Returns false —
	 * leave the attack alone — when there is no slot this tick or another attack is
	 * already held.
	 */
	public static boolean begin(Object module, LocalPlayer player, Entity attacked, double spoofHeight,
			boolean clearClientFall) {
		if (stage != Stage.IDLE || !MovePacketLimiter.isPositionFree() || !HeldAttack.hold(module)) {
			return false;
		}
		owner = module;
		target = attacked;
		height = spoofHeight;
		resetFall = clearClientFall;
		serverY = player.getY() + Math.max(1.6, spoofHeight);
		sendFillers(player, spoofHeight);
		player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), serverY, player.getZ(),
				false, player.horizontalCollision));
		window = MovePacketLimiter.window();
		stage = Stage.CLIMBED;
		return true;
	}

	/** Whether a sequence still has steps to send, including a step-down after an abandon. */
	public static boolean isBusy() {
		return stage != Stage.IDLE;
	}

	/** The module was switched off: never land a banked fall on its account. */
	public static void cancel(Object module) {
		if (stage == Stage.CLIMBED && owner == module) {
			HeldAttack.release(module);
			stage = Stage.STEPPING_DOWN;
		}
	}

	/** Avoid sending a spoof into solid blocks when the raised endpoint is loaded. */
	public static boolean hasRoom(LocalPlayer player, double height) {
		if (player.level() == null) {
			return false;
		}
		BlockPos feet = BlockPos.containing(player.getX(), player.getY() + height, player.getZ());
		return player.level().getBlockState(feet).canBeReplaced()
				&& player.level().getBlockState(feet.above()).canBeReplaced()
				&& player.level().getFluidState(feet).isEmpty()
				&& player.level().getFluidState(feet.above()).isEmpty();
	}

	/** One step per window, from {@code UnluckyClient.tick()}. */
	public static void onTickEnd() {
		if (stage == Stage.IDLE) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.getConnection() == null) {
			// gone from the server: there is nothing left over there to put right
			clear();
			return;
		}
		if (MovePacketLimiter.window() == window || !MovePacketLimiter.isPositionFree()) {
			return;
		}
		window = MovePacketLimiter.window();
		switch (stage) {
			case CLIMBED -> {
				if (!HeldAttack.isHeldBy(owner) || !reachable(player, target)) {
					HeldAttack.release(owner);
					stage = Stage.STEPPING_DOWN;
					stepDown(player);
					return;
				}
				// the fall is banked here, so the hit goes out in the same window
				sendFillers(player, height);
				player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), player.getY() + 0.01,
						player.getZ(), false, player.horizontalCollision));
				Entity hit = target;
				stage = Stage.LANDING;
				HeldAttack.replay(owner, hit);
				if (resetFall) {
					player.fallDistance = 0.0;
					player.setDeltaMovement(player.getDeltaMovement().x,
							Math.max(0.01, player.getDeltaMovement().y), player.getDeltaMovement().z);
				}
			}
			case LANDING -> {
				player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), player.getY(),
						player.getZ(), player.onGround(), player.horizontalCollision));
				clear();
			}
			case STEPPING_DOWN -> stepDown(player);
			default -> clear();
		}
	}

	/** One landed drop of at most {@link #SAFE_STEP}; the last one lands on the client. */
	private static void stepDown(LocalPlayer player) {
		serverY = Math.max(player.getY(), serverY - SAFE_STEP);
		boolean arrived = serverY <= player.getY();
		player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), serverY, player.getZ(),
				arrived ? player.onGround() : true, player.horizontalCollision));
		if (arrived) {
			clear();
		}
	}

	/** Still worth hitting: the held attack would reach it from where the client stands. */
	private static boolean reachable(LocalPlayer player, Entity entity) {
		if (entity == null || !entity.isAlive() || entity.isRemoved()) {
			return false;
		}
		double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + 1.0;
		return player.distanceToSqr(entity) <= reach * reach;
	}

	/**
	 * Status-only packets the server counts toward how far one position may move — they
	 * carry no position, so they do not spend the tick's slot.
	 */
	private static void sendFillers(LocalPlayer player, double spoofHeight) {
		int fillers = Math.min(19, Math.max(0, (int) Math.ceil(spoofHeight / 10.0) - 1));
		for (int i = 0; i < fillers; i++) {
			player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
		}
	}

	private static void clear() {
		stage = Stage.IDLE;
		owner = null;
		target = null;
	}
}
