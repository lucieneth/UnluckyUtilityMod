package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.FriendManager;

/**
 * Sidesteps out of a melee combo.
 *
 * <p><b>What this is not:</b> the client is never told an attack is coming. There
 * is no pre-damage packet — {@code ClientboundDamageEventPacket} is the server
 * <i>reporting</i> a hit it already applied, and a player's swing packet goes out
 * at the same instant their hit lands, not before. So nothing here dodges the
 * first hit; what it does is break the <i>chain</i>, by moving you off their line
 * before the next one.
 *
 * <p>The step is perpendicular to the attacker, which is what actually spoils
 * melee aim, and only ever toward a side that's checked clear: the path has to be
 * free and — while you're standing on something — the far end has to still have
 * floor under it, so this never walks you off a ledge or into a hole. Boxed in on
 * both sides, it does nothing rather than shove you into a wall.
 *
 * <p>It moves you by overwriting velocity, same as TargetStrafe, so it is a
 * motion modification like any other and reads as one to a strict anticheat.
 */
public class Dodge extends Module {
	/** How far below the landing spot still counts as "there's floor here". */
	private static final double LEDGE_DROP = 1.5;
	/** Half-width of the floor probe — narrow enough to fit the gap we'd land in. */
	private static final double PROBE_HALF = 0.3;

	public final ModeSetting trigger = add(new ModeSetting("Trigger", "What starts a dodge", "On hit",
			"On hit", "On swing", "Both"));
	public final NumberSetting duration = add(new NumberSetting("Duration", "How many ticks the step lasts", 4, 1, 10, 1));
	public final NumberSetting strength = add(new NumberSetting("Strength", "Sidestep speed in blocks per tick", 0.28, 0.1, 0.5, 0.01));
	public final NumberSetting swingRange = add(new NumberSetting("Swing range",
			"How close a swinging player has to be to set off On-swing", 4.5, 2.0, 8.0, 0.5));
	public final BooleanSetting onlyGround = add(new BooleanSetting("Only on ground", "Never dodge while airborne", true));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends", "Don't dodge your friends", true));

	/** The direction we're stepping, horizontal and normalized; null when idle. */
	private Vec3 direction;
	private int remaining;

	public Dodge() {
		super("Dodge", "Sidesteps out of melee combos", Category.COMBAT);
	}

	@Override
	protected void onDisable() {
		direction = null;
		remaining = 0;
	}

	/** ClientPacketListenerMixin: the server telling us a hit already landed. */
	public void onDamage(ClientboundDamageEventPacket packet) {
		if (trigger.is("On swing") || mc().player == null || mc().level == null
				|| packet.entityId() != mc().player.getId()) {
			return;
		}
		// sourceCauseId is who's responsible (the shooter), sourceDirectId the thing
		// that touched us — for melee they're the same entity
		if (mc().level.getEntity(packet.sourceCauseId()) instanceof Player attacker) {
			start(attacker);
		}
	}

	/** ClientPacketListenerMixin: someone swung — a hit landing or a miss, we can't tell. */
	public void onAnimate(ClientboundAnimatePacket packet) {
		if (trigger.is("On hit") || packet.getAction() != ClientboundAnimatePacket.SWING_MAIN_HAND
				|| mc().player == null || mc().level == null) {
			return;
		}
		if (mc().level.getEntity(packet.getId()) instanceof Player attacker && swingingAtUs(attacker)) {
			start(attacker);
		}
	}

	/** In melee reach and pointed at us, rather than swinging at the air elsewhere. */
	private boolean swingingAtUs(Player attacker) {
		LocalPlayer player = mc().player;
		double range = swingRange.get();
		if (attacker == player || attacker.distanceToSqr(player) > range * range) {
			return false;
		}
		Vec3 toUs = player.position().subtract(attacker.position());
		Vec3 flat = new Vec3(toUs.x, 0.0, toUs.z);
		if (flat.lengthSqr() < 1.0e-4) {
			return false;
		}
		Vec3 look = attacker.getLookAngle();
		// within ~45 degrees of us on the horizontal
		return new Vec3(look.x, 0.0, look.z).normalize().dot(flat.normalize()) > 0.7;
	}

	private void start(Player attacker) {
		LocalPlayer player = mc().player;
		if (remaining > 0 || attacker == player) {
			return; // already stepping — let it finish rather than chain across the arena
		}
		if (ignoreFriends.get() && FriendManager.isFriend(attacker.getUUID())) {
			return;
		}
		if (onlyGround.get() && !player.onGround()) {
			return;
		}
		Vec3 away = player.position().subtract(attacker.position());
		Vec3 flat = new Vec3(away.x, 0.0, away.z);
		if (flat.lengthSqr() < 1.0e-4) {
			return; // standing inside each other; no meaningful axis to step off
		}
		Vec3 picked = pick(flat.normalize());
		if (picked == null) {
			return; // both sides blocked
		}
		direction = picked;
		remaining = duration.getInt();
	}

	/**
	 * Perpendicular to the attacker's line, whichever side is clear. With both
	 * clear, keep the way we're already moving — that reads as a strafe rather
	 * than a snap, and doesn't throw away the speed we have.
	 */
	private Vec3 pick(Vec3 away) {
		Vec3 left = new Vec3(-away.z, 0.0, away.x);
		Vec3 right = left.reverse();
		boolean leftOk = clear(left);
		boolean rightOk = clear(right);
		if (leftOk && rightOk) {
			Vec3 velocity = mc().player.getDeltaMovement();
			return velocity.x * left.x + velocity.z * left.z >= 0.0 ? left : right;
		}
		if (leftOk) {
			return left;
		}
		return rightOk ? right : null;
	}

	/** Nothing in the way, and something to land on once we get there. */
	private boolean clear(Vec3 direction) {
		LocalPlayer player = mc().player;
		Vec3 step = direction.scale(travel());
		if (!mc().level.noCollision(player, player.getBoundingBox().move(step))) {
			return false;
		}
		if (!player.onGround()) {
			return true; // already airborne: there's no ledge to walk off
		}
		Vec3 destination = player.position().add(step);
		AABB floor = new AABB(destination.x - PROBE_HALF, destination.y - LEDGE_DROP, destination.z - PROBE_HALF,
				destination.x + PROBE_HALF, destination.y, destination.z + PROBE_HALF);
		// something solid down there = floor. Lava and water fall out for free:
		// neither collides, so a step into either reads as a ledge and is refused
		return !mc().level.noCollision(floor);
	}

	/** How far the whole step actually carries us — also the distance we vet. */
	private double travel() {
		return strength.get() * duration.get();
	}

	@Override
	public void onTick() {
		if (remaining <= 0) {
			return;
		}
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || direction == null) {
			onDisable();
			return;
		}
		remaining--;
		// the world moves under us mid-step; stop the moment the way stops being safe
		if (!clear(direction)) {
			direction = null;
			remaining = 0;
			return;
		}
		Vec3 velocity = player.getDeltaMovement();
		double speed = strength.get();
		player.setDeltaMovement(direction.x * speed, velocity.y, direction.z * speed);
	}
}
