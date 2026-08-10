package unlucky.utility.client.module.modules.render;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.Render3D;

/** Predicts held throwable/weapon trajectories and optional live projectile paths. */
public class Trajectories extends Module {
	public final BooleanSetting otherPlayers = add(new BooleanSetting("Other players",
			"Predict held items for other loaded players", true));
	public final BooleanSetting firedProjectiles = add(new BooleanSetting("Fired projectiles",
			"Continue predicting projectiles that are already in flight", false));
	public final BooleanSetting projectileWeapons = add(new BooleanSetting("Projectile weapons",
			"Include drawn bows, charged crossbows, and charged tridents", true));
	public final BooleanSetting throwables = add(new BooleanSetting("Throwables",
			"Include pearls, snowballs, eggs, potions, and experience bottles", true));
	public final BooleanSetting windCharges = add(new BooleanSetting("Wind charges",
			"Include held wind charges", true));
	public final NumberSetting simulationSteps = add(new NumberSetting("Simulation steps",
			"Maximum ticks calculated for each path", 500, 20, 2000, 20));
	public final NumberSetting ignoreFirst = add(new NumberSetting("Ignore first ticks",
			"Hide the first part of each line to expose the distant path", 0, 0, 20, 1));
	public final ColorSetting lineColor = add(new ColorSetting("Line color",
			"Trajectory line color", 0xFFFF9600));
	public final BooleanSetting endpointBox = add(new BooleanSetting("Endpoint box",
			"Draw a box at the predicted collision", true));
	public final ColorSetting endpointColor = add(new ColorSetting("Endpoint color",
			"Predicted collision box color", 0xCCFF9600), endpointBox::get);
	public final BooleanSetting tickBoxes = add(new BooleanSetting("Tick boxes",
			"Draw the projectile position at every simulated tick", false));
	public final NumberSetting tickBoxSize = add(new NumberSetting("Tick box size",
			"Half-size of each per-tick position box", 0.02, 0.01, 0.1, 0.01), tickBoxes::get);

	private record Launch(Vec3 start, Vec3 velocity, double gravity, double drag) {
	}

	public Trajectories() {
		super("Trajectories", "Predicts the trajectory of throwable items when held", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		for (Player player : mc().level.players()) {
			if (player == mc().player || otherPlayers.get()) {
				Launch launch = launchFor(player);
				if (launch != null) draw(player, launch);
			}
		}
		if (firedProjectiles.get()) {
			for (Entity entity : mc().level.entitiesForRendering()) {
				Launch launch = launchFor(entity);
				if (launch != null) draw(entity, launch);
			}
		}
	}

	private Launch launchFor(Player player) {
		ItemStack stack = player.isUsingItem() ? player.getUseItem() : player.getMainHandItem();
		if (!supported(stack.getItem())) {
			stack = player.getOffhandItem();
		}
		Item item = stack.getItem();
		float speed;
		float pitchOffset = 0;
		double gravity;
		double drag = 0.99;
		if (item instanceof BowItem) {
			if (!projectileWeapons.get() || !player.isUsingItem()) return null;
			speed = BowItem.getPowerForTime(player.getTicksUsingItem()) * 3.0f;
			if (speed < 0.1f) return null;
			gravity = 0.05;
		} else if (item instanceof CrossbowItem) {
			if (!projectileWeapons.get()) return null;
			ChargedProjectiles charged = stack.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
			if (charged.isEmpty()) return null;
			speed = charged.contains(net.minecraft.world.item.Items.FIREWORK_ROCKET) ? 1.6f : 3.15f;
			gravity = charged.contains(net.minecraft.world.item.Items.FIREWORK_ROCKET) ? 0.0 : 0.05;
		} else if (item instanceof TridentItem) {
			if (!projectileWeapons.get() || !player.isUsingItem() || player.getTicksUsingItem() < 10) return null;
			speed = 2.5f;
			gravity = 0.05;
		} else if (item instanceof ExperienceBottleItem) {
			if (!throwables.get()) return null;
			speed = 0.7f;
			pitchOffset = -20;
			gravity = 0.07;
		} else if (item instanceof ThrowablePotionItem) {
			if (!throwables.get()) return null;
			speed = 0.5f;
			pitchOffset = -20;
			gravity = 0.05;
		} else if (item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem) {
			if (!throwables.get()) return null;
			speed = 1.5f;
			gravity = 0.03;
		} else if (item instanceof WindChargeItem) {
			if (!windCharges.get()) return null;
			speed = 1.5f;
			gravity = 0.0;
			drag = 0.95;
		} else {
			return null;
		}
		Vec3 velocity = direction(player.getXRot(), player.getYRot(), pitchOffset).scale(speed);
		Vec3 own = player.getDeltaMovement();
		velocity = velocity.add(own.x, player.onGround() ? 0.0 : own.y, own.z);
		return new Launch(player.getEyePosition().add(0, -0.1, 0), velocity, gravity, drag);
	}

	private Launch launchFor(Entity entity) {
		if (!(entity instanceof Projectile)) return null;
		double gravity;
		double drag;
		if (entity instanceof AbstractWindCharge) {
			gravity = 0.0;
			drag = 0.95;
		} else if (entity instanceof AbstractArrow) {
			gravity = 0.05;
			drag = 0.99;
		} else if (entity instanceof AbstractThrownPotion) {
			gravity = 0.05;
			drag = 0.99;
		} else if (entity instanceof ThrownExperienceBottle) {
			gravity = 0.07;
			drag = 0.99;
		} else if (entity instanceof ThrowableItemProjectile) {
			gravity = 0.03;
			drag = 0.99;
		} else {
			return null;
		}
		return new Launch(entity.position(), entity.getDeltaMovement(), gravity, drag);
	}

	private void draw(Entity context, Launch launch) {
		ProjectilePathUtil.Path path = ProjectilePathUtil.simulate(mc().level, context,
				launch.start(), launch.velocity(), launch.gravity(), launch.drag(), simulationSteps.getInt());
		List<Vec3> points = path.points();
		int start = Math.min(ignoreFirst.getInt(), Math.max(0, points.size() - 1));
		for (int i = start + 1; i < points.size(); i++) {
			Render3D.line(points.get(i - 1), points.get(i), lineColor.get(), 1.5f, true);
		}
		if (tickBoxes.get()) {
			double size = tickBoxSize.get();
			for (int i = start; i < points.size(); i++) {
				Vec3 point = points.get(i);
				Render3D.box(new AABB(point.x - size, point.y - size, point.z - size,
						point.x + size, point.y + size, point.z + size), lineColor.get(), 0.5f, 0, true);
			}
		}
		if (endpointBox.get() && path.hit() != null) {
			Vec3 end = path.end();
			double size = 0.18;
			int color = endpointColor.get();
			Render3D.box(new AABB(end.x - size, end.y - size, end.z - size,
					end.x + size, end.y + size, end.z + size), color, 1.5f,
					ColorUtil.withAlpha(color, 45), true);
		}
	}

	private boolean supported(Item item) {
		return item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
				|| item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem
				|| item instanceof ExperienceBottleItem || item instanceof ThrowablePotionItem
				|| item instanceof WindChargeItem;
	}

	private static Vec3 direction(float pitch, float yaw, float pitchOffset) {
		float x = -Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
		float y = -Mth.sin((pitch + pitchOffset) * Mth.DEG_TO_RAD);
		float z = Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
		return new Vec3(x, y, z).normalize();
	}
}
