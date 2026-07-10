package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Attacks the best target in range using silent rotations: the server sees
 * you face the target (body turns in third person) while your camera stays
 * free in first person.
 */
public class Aura extends Module {
	// per-mob whitelists, opened by right-clicking the group toggles in the GUI
	public final unlucky.utility.client.settings.EntityListSetting hostileMobs =
			new unlucky.utility.client.settings.EntityListSetting("Hostile mobs", "Which hostile mobs to target");
	public final unlucky.utility.client.settings.EntityListSetting passiveMobs =
			new unlucky.utility.client.settings.EntityListSetting("Passive mobs", "Which passive mobs to target");

	public final NumberSetting range = add(new NumberSetting("Range", "Attack reach in blocks", 4.2, 2.0, 6.0, 0.1));
	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Target hostile mobs — right-click to pick which", true)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Target passive mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final ModeSetting speed = add(new ModeSetting("Speed", "Attributes = full weapon charge, CPS = flat rate", "Attributes", "Attributes", "CPS"));
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second in CPS mode", 8, 1, 20, 1));
	public final ModeSetting priority = add(new ModeSetting("Priority", "Which target to hit first", "Closest", "Closest", "Health"));
	public final ModeSetting targetPoint = add(new ModeSetting("Target point", "Body part to aim at", "Body", "Head", "Body", "Feet"));
	public final BooleanSetting showHitbox = add(new BooleanSetting("Show hitbox", "Outline the body part being targeted", false));
	public final BooleanSetting hitboxWalls = add(new BooleanSetting("Through walls", "Show the hitbox through blocks", true));
	public final BooleanSetting silent = add(new BooleanSetting("Silent look", "Face the target server-side only", true));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs", "Don't attack with a screen open", true));

	/** The entity Aura is currently locked on, for TargetHUD. Null when idle. */
	public static Entity currentTarget;

	private int ticksSinceAttack;

	public Aura() {
		super("Aura", "Attacks nearby targets", Category.COMBAT);
		// registered for config persistence; hidden from the GUI (no component),
		// reachable through the right-click picker instead
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	protected void onDisable() {
		currentTarget = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			currentTarget = null;
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}
		ticksSinceAttack++;

		Entity target = pickTarget();
		currentTarget = target;
		if (target == null) {
			return;
		}
		Vec3 aim = aimPoint(target);
		if (showHitbox.get()) {
			Render3D.box(partBox(target.getBoundingBox(), aim), Theme.accent1, 1.5f,
					ColorUtil.withAlpha(Theme.accent1, 45), hitboxWalls.get());
		}
		if (silent.get()) {
			RotationManager.lookAt(aim);
		}
		if (CombatUtil.ready(speed.is("Attributes"), cps.get(), ticksSinceAttack)) {
			CombatUtil.attack(target);
			ticksSinceAttack = 0;
		}
	}

	/** Where on the target we aim: eye height, box center, or just above the feet. */
	private Vec3 aimPoint(Entity target) {
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();
		return switch (targetPoint.get()) {
			case "Head" -> new Vec3(center.x, Math.min(target.getEyeY(), box.maxY - 0.05), center.z);
			case "Feet" -> new Vec3(center.x, box.minY + box.getYsize() * 0.1, center.z);
			default -> center;
		};
	}

	/** A horizontal slice of the hitbox (1/3 of its height) around the aim point. */
	private static AABB partBox(AABB box, Vec3 aim) {
		double half = box.getYsize() / 6.0;
		double y0 = Math.max(aim.y - half, box.minY);
		double y1 = Math.min(aim.y + half, box.maxY);
		return new AABB(box.minX, y0, box.minZ, box.maxX, y1, box.maxZ).inflate(0.03);
	}

	private Entity pickTarget() {
		Entity best = null;
		double bestScore = Double.MAX_VALUE;
		double rangeSq = range.get() * range.get();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!CombatUtil.validTarget(entity, players.get(), hostiles.get(), passives.get(), hostileMobs, passiveMobs)) {
				continue;
			}
			double distSq = mc().player.distanceToSqr(entity);
			if (distSq > rangeSq) {
				continue;
			}
			double score = priority.is("Health") && entity instanceof LivingEntity living
					? living.getHealth()
					: distSq;
			if (score < bestScore) {
				bestScore = score;
				best = entity;
			}
		}
		return best;
	}
}
