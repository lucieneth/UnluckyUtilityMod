package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.ProjectilePathUtil.ProjectileType;
import unlucky.utility.client.util.Render3D;

/** Predicts held, remote-player and already-fired projectiles through one shared simulator. */
public class Trajectories extends Module {
	private static final double[] MULTISHOT_YAW = { -10.0, 0.0, 10.0 };

	public final BooleanSetting bows = add(new BooleanSetting("Bow", "Include drawn bows", true));
	public final BooleanSetting crossbows = add(new BooleanSetting("Crossbow",
			"Include charged crossbows and multishot paths", true));
	public final BooleanSetting tridents = add(new BooleanSetting("Trident",
			"Include charged and already-thrown tridents", true));
	public final BooleanSetting snowballs = add(new BooleanSetting("Snowball", "Include snowballs", true));
	public final BooleanSetting eggs = add(new BooleanSetting("Egg", "Include eggs", true));
	public final BooleanSetting pearls = add(new BooleanSetting("Ender pearl", "Include ender pearls", true));
	public final BooleanSetting experienceBottles = add(new BooleanSetting("XP bottle",
			"Include thrown experience bottles", true));
	public final BooleanSetting potions = add(new BooleanSetting("Splash/lingering potion",
			"Include thrown potions", true));
	public final BooleanSetting fishingRods = add(new BooleanSetting("Fishing rod",
			"Include fishing casts and live bobbers", true));
	public final BooleanSetting windCharges = add(new BooleanSetting("Wind charge",
			"Include wind charges", true));

	public final BooleanSetting heldProjectile = add(new BooleanSetting("Held projectile",
			"Predict the projectile currently held by the local player", true));
	public final BooleanSetting otherPlayers = add(new BooleanSetting("Other players",
			"Predict held items for other loaded players", false));
	public final BooleanSetting firedProjectiles = add(new BooleanSetting("Fired projectiles",
			"Continue predicting projectiles that are already in flight", false));
	public final BooleanSetting accurateSimulation = add(new BooleanSetting("Accurate simulation",
			"Stop paths on entity AABBs as well as terrain", true));
	public final NumberSetting simulationSteps = add(new NumberSetting("Simulation steps",
			"Maximum ticks calculated for each path", 300, 20, 2000, 20));
	public final NumberSetting ignoreFirst = add(new NumberSetting("Ignore first ticks",
			"Hide this many ticks from already-fired paths", 3, 0, 20, 1));

	public final ColorSetting lineColor = add(new ColorSetting("Line color",
			"Trajectory line color", 0xFFFF9600));
	public final NumberSetting lineWidth = add(new NumberSetting("Line width",
			"Width of trajectory lines", 1.5, 0.5, 5.0, 0.1));
	public final BooleanSetting impactMarker = add(new BooleanSetting("Impact marker",
			"Draw a box at the predicted collision", true));
	public final ColorSetting impactColor = add(new ColorSetting("Impact color",
			"Predicted collision box color", 0xCCFF9600), impactMarker::get);
	public final BooleanSetting highlightEntity = add(new BooleanSetting("Highlight hit entity",
			"Outline the entity the projectile is predicted to hit", true));
	public final ColorSetting entityColor = add(new ColorSetting("Entity color",
			"Predicted hit-entity outline color", 0xCCFF3C3C), highlightEntity::get);
	public final BooleanSetting positionBoxes = add(new BooleanSetting("Position boxes",
			"Draw the projectile position at every simulated tick", false));
	public final NumberSetting positionBoxSize = add(new NumberSetting("Position box size",
			"Half-size of each per-tick position box", 0.02, 0.01, 0.1, 0.01), positionBoxes::get);

	private record Launch(Vec3 start, Vec3 velocity, ProjectileType type, boolean fired) {
	}

	private final ProjectilePathUtil.ResultBuffer pathBuffer = new ProjectilePathUtil.ResultBuffer();
	private final ArrayList<Launch> heldLaunches = new ArrayList<>(3);
	private final ArrayList<Vec3> multishotVelocities = new ArrayList<>(3);

	public Trajectories() {
		super("Trajectories", "Predicts held and fired projectile paths",
				Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) return;
		if (heldProjectile.get()) {
			for (Player player : mc().level.players()) {
				if (player == mc().player || otherPlayers.get()) {
					for (Launch launch : launchesFor(player)) draw(player, launch);
				}
			}
		}
		if (firedProjectiles.get()) {
			for (Entity entity : mc().level.entitiesForRendering()) {
				Launch launch = launchFor(entity);
				if (launch != null) draw(entity, launch);
			}
		}
	}

	private List<Launch> launchesFor(Player player) {
		heldLaunches.clear();
		ItemStack stack = player.isUsingItem() && supported(player.getUseItem().getItem())
				? player.getUseItem() : player.getMainHandItem();
		if (!supported(stack.getItem())) stack = player.getOffhandItem();
		Item item = stack.getItem();
		ProjectileType type;
		int useTicks = player.getTicksUsingItem();
		ChargedProjectiles charged = ChargedProjectiles.EMPTY;
		if (item instanceof BowItem) {
			if (!bows.get() || !player.isUsingItem()) return heldLaunches;
			type = ProjectileType.BOW_ARROW;
			if (type.initialSpeed(useTicks) < 0.1) return heldLaunches;
		} else if (item instanceof CrossbowItem) {
			if (!crossbows.get()) return heldLaunches;
			charged = stack.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
			if (charged.isEmpty()) return heldLaunches;
			type = charged.contains(net.minecraft.world.item.Items.FIREWORK_ROCKET)
					? ProjectileType.CROSSBOW_FIREWORK : ProjectileType.CROSSBOW_ARROW;
		} else if (item instanceof TridentItem) {
			if (!tridents.get() || !player.isUsingItem() || useTicks < 10) return heldLaunches;
			type = ProjectileType.TRIDENT;
		} else if (item instanceof ExperienceBottleItem) {
			if (!experienceBottles.get()) return heldLaunches;
			type = ProjectileType.EXPERIENCE_BOTTLE;
		} else if (item instanceof ThrowablePotionItem) {
			if (!potions.get()) return heldLaunches;
			type = ProjectileType.POTION;
		} else if (item instanceof SnowballItem) {
			if (!snowballs.get()) return heldLaunches;
			type = ProjectileType.SNOWBALL;
		} else if (item instanceof EggItem) {
			if (!eggs.get()) return heldLaunches;
			type = ProjectileType.EGG;
		} else if (item instanceof EnderpearlItem) {
			if (!pearls.get()) return heldLaunches;
			type = ProjectileType.ENDER_PEARL;
		} else if (item instanceof FishingRodItem) {
			if (!fishingRods.get()) return heldLaunches;
			type = ProjectileType.FISHING_BOBBER;
		} else if (item instanceof WindChargeItem) {
			if (!windCharges.get()) return heldLaunches;
			type = ProjectileType.WIND_CHARGE;
		} else {
			return heldLaunches;
		}

		Vec3 movement = player.getDeltaMovement();
		Vec3 inherited = new Vec3(movement.x, player.onGround() ? 0.0 : movement.y, movement.z);
		Vec3 start = player.getEyePosition().add(0, -0.1, 0);
		if (item instanceof CrossbowItem && charged.items().size() > 1) {
			Vec3 centre = ProjectilePathUtil.launchVelocity(type, useTicks, player.getXRot(),
					player.getYRot(), Vec3.ZERO);
			ProjectilePathUtil.multishot(centre, MULTISHOT_YAW, multishotVelocities);
			for (Vec3 velocity : multishotVelocities) {
				heldLaunches.add(new Launch(start, velocity.add(inherited), type, false));
			}
		} else {
			heldLaunches.add(new Launch(start, ProjectilePathUtil.launchVelocity(type, useTicks,
					player.getXRot(), player.getYRot(), inherited), type, false));
		}
		return heldLaunches;
	}

	private Launch launchFor(Entity entity) {
		if (!(entity instanceof Projectile)) return null;
		ProjectileType type;
		if (entity instanceof AbstractWindCharge) {
			if (!windCharges.get()) return null;
			type = ProjectileType.WIND_CHARGE;
		} else if (entity instanceof FireworkRocketEntity) {
			if (!crossbows.get()) return null;
			type = ProjectileType.CROSSBOW_FIREWORK;
		} else if (entity instanceof ThrownTrident) {
			if (!tridents.get()) return null;
			type = ProjectileType.TRIDENT;
		} else if (entity instanceof AbstractArrow arrow) {
			boolean crossbow = arrow.getWeaponItem().getItem() instanceof CrossbowItem;
			if (crossbow ? !crossbows.get() : !bows.get()) return null;
			type = crossbow ? ProjectileType.CROSSBOW_ARROW : ProjectileType.BOW_ARROW;
		} else if (entity instanceof AbstractThrownPotion) {
			if (!potions.get()) return null;
			type = ProjectileType.POTION;
		} else if (entity instanceof ThrownExperienceBottle) {
			if (!experienceBottles.get()) return null;
			type = ProjectileType.EXPERIENCE_BOTTLE;
		} else if (entity instanceof Snowball) {
			if (!snowballs.get()) return null;
			type = ProjectileType.SNOWBALL;
		} else if (entity instanceof ThrownEgg) {
			if (!eggs.get()) return null;
			type = ProjectileType.EGG;
		} else if (entity instanceof ThrownEnderpearl) {
			if (!pearls.get()) return null;
			type = ProjectileType.ENDER_PEARL;
		} else if (entity instanceof FishingHook) {
			if (!fishingRods.get()) return null;
			type = ProjectileType.FISHING_BOBBER;
		} else {
			return null;
		}
		return new Launch(entity.position(), entity.getDeltaMovement(), type, true);
	}

	private void draw(Entity context, Launch launch) {
		ProjectilePathUtil.ResultBuffer path = ProjectilePathUtil.simulate(mc().level, context,
				launch.start(), launch.velocity(), launch.type(), simulationSteps.getInt(),
				accurateSimulation.get(), entity -> !(context instanceof Projectile projectile
						&& entity == projectile.getOwner()), pathBuffer);
		List<Vec3> points = path.points();
		int ignored = launch.fired() ? ignoreFirst.getInt() : 0;
		int start = Math.min(ignored, Math.max(0, points.size() - 1));
		for (int i = start + 1; i < points.size(); i++) {
			Render3D.line(points.get(i - 1), points.get(i), lineColor.get(),
					lineWidth.getFloat(), true);
		}
		if (positionBoxes.get()) {
			double size = positionBoxSize.get();
			for (int i = start; i < points.size(); i++) {
				Vec3 point = points.get(i);
				Render3D.box(new AABB(point.x - size, point.y - size, point.z - size,
						point.x + size, point.y + size, point.z + size),
						lineColor.get(), 0.5f, 0, true);
			}
		}
		if (impactMarker.get() && path.hit() != null) {
			Vec3 end = path.end();
			double size = 0.18;
			int color = impactColor.get();
			Render3D.box(new AABB(end.x - size, end.y - size, end.z - size,
					end.x + size, end.y + size, end.z + size), color,
					lineWidth.getFloat(), ColorUtil.withAlpha(color, 45), true);
		}
		if (highlightEntity.get() && path.hit() instanceof EntityHitResult hit) {
			int color = entityColor.get();
			Render3D.box(hit.getEntity().getBoundingBox().inflate(0.03), color,
					lineWidth.getFloat(), ColorUtil.withAlpha(color, 35), true);
		}
	}

	private boolean supported(Item item) {
		return item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
				|| item instanceof SnowballItem || item instanceof EggItem
				|| item instanceof EnderpearlItem || item instanceof ExperienceBottleItem
				|| item instanceof ThrowablePotionItem || item instanceof FishingRodItem
				|| item instanceof WindChargeItem;
	}
}
