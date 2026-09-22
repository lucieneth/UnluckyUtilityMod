package unlucky.utility.client.module.modules.player;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.MovePacketLimiter;
import unlucky.utility.client.util.Render3D;

/**
 * Packet-steps next to a target out of reach, acts from there, and steps back once the
 * actions stop.
 *
 * <p><b>One step since 26.3.</b> Up to 26.2 this walked a whole path out, acted and walked
 * it back inside a single call — up to 128 blocks. 26.3 takes one position per client tick
 * ({@link MovePacketLimiter}) and moves a player about ten blocks per position, so only the
 * first step of that path ever reached the server. What still works inside one call is a
 * single step out with the action right behind it, which puts the reach at one
 * {@link #packetStep} past vanilla.
 *
 * <p><b>The way back needs a tick of its own</b>, so the module stays out while the actions
 * keep coming — a block being broken is a start, a run of progress ticks and a stop, all of
 * which must come from within range — and steps back once a full tick has passed without
 * one. While it is out, vanilla's own position packets are held back
 * ({@link #suppressesMovementPackets()}): the next one would put the server back beside you
 * and every action after it out of reach.
 *
 * <p>Walking while out ends it early: the step back has to stay inside what the server
 * accepts from one position, and the client drifting away from where it stepped out is
 * exactly what would push it past that.
 */
public class InfiniteInteract extends Module {
	/** How close to the target the step lands; well inside every vanilla range. */
	private static final double APPROACH = 2.5;
	/** Client movement while out that ends it: step plus drift must stay one legal move. */
	private static final double MAX_DRIFT = 0.5;

	public final NumberSetting cooldown = add(new NumberSetting("Action cooldown", "Ticks to wait between distant actions", 0, 0, 100, 1));
	public enum Action {
		ATTACK_ENTITY, INTERACT_ENTITY, BREAK_BLOCK, INTERACT_BLOCK
	}

	public final BooleanSetting notSneaking = add(new BooleanSetting("Not while sneaking",
			"Leave distant actions vanilla while the sneak key is held", true));
	public final BooleanSetting attackEntities = add(new BooleanSetting("Attack entities",
			"Allow distant entity attacks", true));
	public final BooleanSetting interactEntities = add(new BooleanSetting("Interact entities",
			"Allow distant entity interactions", true));
	public final BooleanSetting breakBlocks = add(new BooleanSetting("Break blocks",
			"Allow distant block breaking", true));
	public final BooleanSetting interactBlocks = add(new BooleanSetting("Interact blocks",
			"Allow distant block interactions", true));
	public final NumberSetting packetStep = add(new NumberSetting("Packet step",
			"How far the step out may go; the reach is this plus 2.5", 8, 1, 9.5, 0.5));
	public final BooleanSetting showTrail = add(new BooleanSetting("Show trail",
			"Render the most recent packet path", true));
	public final BooleanSetting showSteps = add(new BooleanSetting("Show steps",
			"Draw a box at each packet position", false));
	public final NumberSetting trailSeconds = add(new NumberSetting("Trail time",
			"Seconds to retain the latest path", 3, 0, 10, 0.5));
	public final ColorSetting trailColor = add(new ColorSetting("Trail color",
			"Color of the packet path", 0xFFFFFFFF), showTrail::get);
	public final ColorSetting stepColor = add(new ColorSetting("Step color",
			"Color of packet step boxes", 0x60FF9C00), showSteps::get);

	/** Where the server has us while stepped out; null while home. */
	private Vec3 out;
	/** Where the client stood when it stepped out, for the drift check. */
	private Vec3 home;
	/** The last window an action went out from {@link #out}. */
	private long lastActionWindow;
	private List<Vec3> lastPath = List.of();
	private long trailUntil;
	private int cooldownTicks;

	public InfiniteInteract() {
		super("InfiniteInteract", "Packet-steps into range for distant actions", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Range used by LocalPlayer's crosshair raycast while the module is enabled. */
	public double targetingRange(double vanilla) {
		return isEnabled() ? Math.max(vanilla, packetStep.get() + APPROACH) : vanilla;
	}

	/** LocalPlayerMixin: vanilla's position would pull the server back from the step. */
	public boolean suppressesMovementPackets() {
		return out != null;
	}

	public boolean begin(Entity entity, Action action) {
		return entity != null && begin(entity.getBoundingBox().getCenter(), action);
	}

	public boolean begin(BlockPos pos, Action action) {
		return pos != null && begin(Vec3.atCenterOf(pos), action);
	}

	/**
	 * MultiPlayerGameModeMixin, at each action's HEAD. Puts the server within reach of
	 * {@code target} before the action's packet goes out, or leaves the action to vanilla.
	 */
	private boolean begin(Vec3 target, Action action) {
		LocalPlayer player = mc().player;
		if (player == null || !allowed(action) || (notSneaking.get() && player.isShiftKeyDown())) {
			return false;
		}
		double vanillaRange = switch (action) {
			case ATTACK_ENTITY, INTERACT_ENTITY -> player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
			case BREAK_BLOCK, INTERACT_BLOCK -> player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		};
		// already out and close enough: the server has us there, nothing to send
		if (out != null && out.distanceTo(target) <= vanillaRange) {
			lastActionWindow = MovePacketLimiter.window();
			return true;
		}
		Vec3 real = player.position();
		double distance = real.distanceTo(target);
		if (distance <= vanillaRange || cooldownTicks > 0 || !MovePacketLimiter.isPositionFree()) {
			return false;
		}
		Vec3 direction = target.subtract(real).normalize();
		Vec3 destination = target.subtract(direction.scale(Math.min(APPROACH, distance - 0.1)));
		// one legal move out, and — from wherever the server has us now — one legal move there
		Vec3 from = out != null ? out : real;
		if (real.distanceTo(destination) > packetStep.get() || from.distanceTo(destination) > packetStep.get()) {
			return false;
		}
		// flagged grounded: the server stops counting a fall and never sees us floating
		player.connection.send(new ServerboundMovePlayerPacket.Pos(destination.x, destination.y,
				destination.z, true, player.horizontalCollision));
		if (out == null) {
			home = real;
		}
		out = destination;
		lastActionWindow = MovePacketLimiter.window();
		lastPath = List.of(real, destination);
		trailUntil = System.currentTimeMillis() + (long) (trailSeconds.get() * 1000.0);
		return true;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) {
			out = null;
			return;
		}
		if (cooldownTicks > 0) cooldownTicks--;
		// A full window without an action — or the client walking away — and it is over.
		if (out != null && MovePacketLimiter.isPositionFree()
				&& (MovePacketLimiter.window() - lastActionWindow >= 2
						|| player.position().distanceTo(home) > MAX_DRIFT)) {
			stepBack(player);
		}
		if (System.currentTimeMillis() > trailUntil || lastPath.size() < 2) {
			return;
		}
		if (showTrail.get()) {
			for (int i = 1; i < lastPath.size(); i++) {
				Render3D.line(lastPath.get(i - 1), lastPath.get(i), trailColor.get(), 1.5f, true);
			}
		}
		if (showSteps.get()) {
			int color = stepColor.get();
			for (Vec3 point : lastPath) {
				Render3D.box(new AABB(point.x - 0.3, point.y, point.z - 0.3,
						point.x + 0.3, point.y + 1.8, point.z + 0.3), color, 1.0f,
						ColorUtil.withAlpha(color, Math.min(48, (color >>> 24) & 0xFF)), true);
			}
		}
	}

	private void stepBack(LocalPlayer player) {
		player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(), player.getY(), player.getZ(),
				player.onGround(), player.horizontalCollision));
		out = null;
		cooldownTicks = cooldown.getInt();
	}

	private boolean allowed(Action action) {
		return switch (action) {
			case ATTACK_ENTITY -> attackEntities.get();
			case INTERACT_ENTITY -> interactEntities.get();
			case BREAK_BLOCK -> breakBlocks.get();
			case INTERACT_BLOCK -> interactBlocks.get();
		};
	}

	/**
	 * Steps back now if the tick allows. If it does not, vanilla's own next position
	 * does it — the step is one legal move, so the way back is too.
	 */
	@Override
	protected void onDisable() {
		LocalPlayer player = mc().player;
		if (out != null && player != null && MovePacketLimiter.isPositionFree()) {
			stepBack(player);
		}
		out = null;
		lastPath = List.of();
	}
}
