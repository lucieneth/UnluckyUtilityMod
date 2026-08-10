package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
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
import unlucky.utility.client.util.Render3D;

/** Packet-steps near a distant target for one action, then returns immediately. */
public class InfiniteInteract extends Module {
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
	public final NumberSetting maxRange = add(new NumberSetting("Maximum range",
			"Furthest target the module will attempt", 128, 8, 512, 1));
	public final NumberSetting packetStep = add(new NumberSetting("Packet step",
			"Maximum distance between movement packets", 8, 1, 9.5, 0.5));
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

	private record Active(Vec3 real, List<Vec3> forward) {
	}

	private Active active;
	private List<Vec3> lastPath = List.of();
	private long trailUntil;

	public InfiniteInteract() {
		super("InfiniteInteract", "Temporarily packet-steps into range for distant actions", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Range used by LocalPlayer's crosshair raycast while the module is enabled. */
	public double targetingRange(double vanilla) {
		return isEnabled() ? Math.max(vanilla, maxRange.get()) : vanilla;
	}

	public boolean begin(Entity entity, Action action) {
		return entity != null && begin(entity.getBoundingBox().getCenter(), action);
	}

	public boolean begin(BlockPos pos, Action action) {
		return pos != null && begin(Vec3.atCenterOf(pos), action);
	}

	private boolean begin(Vec3 target, Action action) {
		LocalPlayer player = mc().player;
		if (active != null || player == null || !allowed(action)
				|| (notSneaking.get() && player.isShiftKeyDown())) {
			return false;
		}
		Vec3 real = player.position();
		double distance = real.distanceTo(target);
		double vanillaRange = switch (action) {
			case ATTACK_ENTITY, INTERACT_ENTITY -> player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
			case BREAK_BLOCK, INTERACT_BLOCK -> player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		};
		if (distance <= vanillaRange || distance > maxRange.get()) {
			return false;
		}
		Vec3 direction = target.subtract(real).normalize();
		Vec3 destination = target.subtract(direction.scale(Math.min(2.5, distance - 0.1)));
		List<Vec3> path = segmented(real, destination, packetStep.get());
		for (Vec3 point : path) {
			send(point, false);
		}
		active = new Active(real, path);
		List<Vec3> renderedPath = new ArrayList<>(path.size() + 1);
		renderedPath.add(real);
		renderedPath.addAll(path);
		lastPath = renderedPath;
		trailUntil = System.currentTimeMillis() + (long) (trailSeconds.get() * 1000.0);
		return true;
	}

	public void finish() {
		if (active == null || mc().player == null) {
			active = null;
			return;
		}
		List<Vec3> path = active.forward();
		for (int i = path.size() - 2; i >= 0; i--) {
			send(path.get(i), false);
		}
		send(active.real(), mc().player.onGround());
		active = null;
	}

	@Override
	public void onTick() {
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

	private boolean allowed(Action action) {
		return switch (action) {
			case ATTACK_ENTITY -> attackEntities.get();
			case INTERACT_ENTITY -> interactEntities.get();
			case BREAK_BLOCK -> breakBlocks.get();
			case INTERACT_BLOCK -> interactBlocks.get();
		};
	}

	private static List<Vec3> segmented(Vec3 start, Vec3 end, double step) {
		double distance = start.distanceTo(end);
		int count = Math.max(1, (int) Math.ceil(distance / step));
		List<Vec3> points = new ArrayList<>(count);
		for (int i = 1; i <= count; i++) {
			points.add(start.lerp(end, i / (double) count));
		}
		return points;
	}

	private void send(Vec3 position, boolean onGround) {
		LocalPlayer player = mc().player;
		player.connection.send(new ServerboundMovePlayerPacket.Pos(position.x, position.y, position.z,
				onGround, player.horizontalCollision));
	}

	@Override
	protected void onDisable() {
		finish();
		lastPath = List.of();
	}
}
