package unlucky.utility.client.module.modules.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ProjectileAimSolver;
import unlucky.utility.client.util.ProjectilePathUtil.ProjectileType;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/** Ballistic aim only; firing remains entirely under the player's control. */
public class BowAimbot extends Module {
	public final BooleanSetting bows = add(new BooleanSetting("Bow", "Aim bows", true));
	public final BooleanSetting crossbows = add(new BooleanSetting("Crossbow", "Aim loaded crossbows", true));
	public final BooleanSetting onlyUsing = add(new BooleanSetting("Only while using weapon", "Require charging or a loaded crossbow", true));
	public final NumberSetting range = add(new NumberSetting("Range", "Maximum target range", 30, 4, 80, 1));
	public final NumberSetting fov = add(new NumberSetting("FOV", "Full targeting cone", 120, 10, 360, 5));
	public final BooleanSetting players = add(new BooleanSetting("Players", "Target players", true));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players", "Include client-side practice players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostile", "Target hostile mobs", false));
	public final BooleanSetting passives = add(new BooleanSetting("Passive", "Target passive mobs", false));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends", "Never aim at friends", true));
	public final BooleanSetting lineOfSight = add(new BooleanSetting("Require line of sight", "Require direct visibility as well as a clear arc", true));
	public final ModeSetting priority = add(new ModeSetting("Priority", "Target ranking", "Smallest angle", "Smallest angle", "Closest", "Lowest health"));
	public final BooleanSetting prediction = add(new BooleanSetting("Prediction", "Lead target velocity by simulated flight time", true));
	public final NumberSetting lead = add(new NumberSetting("Lead strength", "Multiplier for target velocity", 1.0, 0, 2, 0.05));
	public final ModeSetting aimPoint = add(new ModeSetting("Aim point", "Part of the target box", "Body", "Body", "Head", "Closest visible point"));
	public final ModeSetting rotation = add(new ModeSetting("Rotation", "Visible moves the camera; Silent moves only packets/model", "Silent", "Visible", "Silent"));
	public final NumberSetting maxYaw = add(new NumberSetting("Max yaw speed", "Degrees per tick", 45, 1, 180, 1));
	public final NumberSetting maxPitch = add(new NumberSetting("Max pitch speed", "Degrees per tick", 45, 1, 180, 1));
	private final ProjectileAimSolver.Workspace workspace = new ProjectileAimSolver.Workspace();

	public BowAimbot() {
		super("BowAimbot", "Leads projectile aim without firing", Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		if (mc().player == null || mc().level == null || mc().gui.screen() != null) return;
		ItemStack stack = mc().player.getMainHandItem();
		ProjectileType type;
		int useTicks;
		if (stack.getItem() instanceof BowItem && bows.get()) {
			type = ProjectileType.BOW_ARROW;
			useTicks = mc().player.getTicksUsingItem();
			if (onlyUsing.get() && (!mc().player.isUsingItem() || mc().player.getUseItem() != stack)) return;
		} else if (stack.getItem() instanceof CrossbowItem && crossbows.get()) {
			type = ProjectileType.CROSSBOW_ARROW;
			useTicks = CrossbowItem.getChargeDuration(stack, mc().player);
			if (onlyUsing.get() && !mc().player.isUsingItem() && !CrossbowItem.isCharged(stack)) return;
		} else return;
		LivingEntity target = TargetingUtil.select(mc().player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(players.get(), hostiles.get(), passives.get())
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(range.get()).fov(fov.get())
						.lineOfSight(lineOfSight.get()).priority(targetPriority()));
		if (target == null) return;
		AABB box = aimBox(target);
		ProjectileAimSolver.Solution solution = ProjectileAimSolver.solve(new ProjectileAimSolver.Request(
				mc().level, mc().player, mc().player.getEyePosition(), type, useTicks, box,
				prediction.get() ? target.getDeltaMovement().scale(lead.get()) : Vec3.ZERO,
				mc().player.getDeltaMovement(), 160, lineOfSight.get()), workspace);
		if (!solution.valid()) return;
		float yaw = approach(mc().player.getYRot(), solution.yaw(), maxYaw.getFloat());
		float pitch = Mth.clamp(approach(mc().player.getXRot(), solution.pitch(), maxPitch.getFloat()), -90, 90);
		if (rotation.is("Visible")) { mc().player.setYRot(yaw); mc().player.setXRot(pitch); }
		else RotationManager.rotateIfAllowed(yaw, pitch, RotationManager.PRIORITY_FUNCTIONAL);
	}

	private TargetingUtil.Priority targetPriority() {
		return priority.is("Closest") ? TargetingUtil.Priority.CLOSEST
				: priority.is("Lowest health") ? TargetingUtil.Priority.LOWEST_HEALTH
				: TargetingUtil.Priority.SMALLEST_ANGLE;
	}

	private AABB aimBox(LivingEntity target) {
		AABB box = target.getBoundingBox();
		if (aimPoint.is("Head")) return new AABB(box.minX, box.maxY - box.getYsize() * 0.25, box.minZ, box.maxX, box.maxY, box.maxZ);
		if (aimPoint.is("Closest visible point")) {
			Vec3 eye = mc().player.getEyePosition();
			Vec3 p = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ));
			return new AABB(p, p).inflate(0.08);
		}
		return box;
	}

	private static float approach(float current, float wanted, float max) {
		return current + Mth.clamp(Mth.wrapDegrees(wanted - current), -max, max);
	}
}
