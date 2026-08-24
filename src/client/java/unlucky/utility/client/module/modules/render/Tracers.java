package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Standalone tracer lines for loaded entities.
 *
 * <p>This intentionally does not reuse 2DESP's tracer checkbox. That layer only knows about
 * players whose screen-space box projected successfully; a standalone tracer must have its own
 * target filter and keep pointing at entities even when the box, label and silhouette modules
 * are all off. Drawing stays in the ordinary HUD extraction pass, so it neither adds another
 * entity render nor competes with Shader's outline framebuffer.
 *
 * <p>The shape follows Meteor's Tracers module — configurable entity groups, body target,
 * distance colours, friend handling and the optional vertical stem — expressed through this
 * client's shared mob pickers and renderer. Targets are selected on the tick and projected on
 * every rendered frame: that keeps moving entities smooth without adding a second entity pass.
 */
public class Tracers extends Module {
	private static final int MAX_TARGETS = 256;

	private enum Kind {
		PLAYER,
		HOSTILE,
		PASSIVE,
		ITEM,
		VEHICLE,
		PROJECTILE,
		OTHER
	}

	// Per-mob filters use the same right-click picker as Aura/TriggerBot.
	public final EntityListSetting hostileMobs = new EntityListSetting("Hostile mobs",
			"Which hostile mobs get tracers");
	public final EntityListSetting passiveMobs = new EntityListSetting("Passive mobs",
			"Which passive and neutral mobs get tracers");

	public final BooleanSetting players = add(new BooleanSetting("Players",
			"Draw tracers to other players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles",
			"Draw tracers to hostile mobs — right-click to pick which", false)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives",
			"Draw tracers to passive and neutral mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final BooleanSetting items = add(new BooleanSetting("Items",
			"Draw tracers to dropped items", false));
	public final BooleanSetting vehicles = add(new BooleanSetting("Vehicles",
			"Draw tracers to boats and minecarts", false));
	public final BooleanSetting projectiles = add(new BooleanSetting("Projectiles",
			"Draw tracers to loaded projectiles", false));
	public final BooleanSetting others = add(new BooleanSetting("Others",
			"Draw tracers to remaining loaded entity types", false));

	public final BooleanSetting self = add(new BooleanSetting("Self",
			"Trace your own body when a detached camera can see it", false));
	public final BooleanSetting invisible = add(new BooleanSetting("Invisible entities",
			"Include invisible entities", true));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Do not draw tracers to friends", false));
	public final BooleanSetting friendColors = add(new BooleanSetting("Friend color",
			"Use the shared friend blue instead of the selected color mode", true),
			() -> !ignoreFriends.get());
	public final NumberSetting range = add(new NumberSetting("Range",
			"Maximum tracer distance", 256, 8, 512, 8));

	public final ModeSetting origin = add(new ModeSetting("Origin",
			"Where tracer lines start on screen", "Bottom", "Bottom", "Crosshair"));
	public final ModeSetting target = add(new ModeSetting("Target",
			"Part of the entity the line points to", "Body", "Head", "Body", "Feet"));
	public final BooleanSetting stem = add(new BooleanSetting("Stem",
			"Draw a vertical line through each target", true));
	public final NumberSetting lineWidth = add(new NumberSetting("Line width",
			"Tracer and stem width", 1.0, 0.5, 5.0, 0.1));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Keep tracers visible behind terrain", true));

	public final ModeSetting colorMode = add(new ModeSetting("Color mode",
			"Type uses separate entity-group colors; Distance blends near to far",
			"Type", "Type", "Distance", "Static", "Theme"));
	public final ColorSetting staticColor = add(new ColorSetting("Static color",
			"Color used by Static", 0xFFD0D0D0), () -> colorMode.is("Static"));
	public final ColorSetting nearColor = add(new ColorSetting("Near color",
			"Distance color at your position", 0xFF55FF55), () -> colorMode.is("Distance"));
	public final ColorSetting farColor = add(new ColorSetting("Far color",
			"Distance color at maximum range", 0xFFFF5555), () -> colorMode.is("Distance"));
	public final ColorSetting playerColor = add(new ColorSetting("Player color",
			"Other-player tracer color", 0xFFD0D0D0), () -> colorMode.is("Type"));
	public final ColorSetting hostileColor = add(new ColorSetting("Hostile color",
			"Hostile-mob tracer color", 0xFFFF6B6B), () -> colorMode.is("Type"));
	public final ColorSetting passiveColor = add(new ColorSetting("Passive color",
			"Passive and neutral mob tracer color", 0xFF91E691), () -> colorMode.is("Type"));
	public final ColorSetting itemColor = add(new ColorSetting("Item color",
			"Dropped-item tracer color", 0xFFFFD966), () -> colorMode.is("Type"));
	public final ColorSetting vehicleColor = add(new ColorSetting("Vehicle color",
			"Boat and minecart tracer color", 0xFF9CE8A3), () -> colorMode.is("Type"));
	public final ColorSetting projectileColor = add(new ColorSetting("Projectile color",
			"Projectile tracer color", 0xFFB478FF), () -> colorMode.is("Type"));
	public final ColorSetting otherColor = add(new ColorSetting("Other color",
			"Fallback tracer color", 0xFFAAAAAF), () -> colorMode.is("Type"));

	private record Target(Entity entity, Kind kind, boolean friend) {
	}

	private final List<Target> cached = new ArrayList<>();
	private final double[] targetProjection = new double[3];
	private final double[] feetProjection = new double[3];
	private final double[] headProjection = new double[3];
	private int count;

	public Tracers() {
		super("Tracers", "Draws lines to selected entities", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
		// These settings persist but are edited through the two Boolean rows above.
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	public void onTick() {
		cached.clear();
		if (mc().level == null || mc().player == null) {
			return;
		}
		double maximum = range.get();
		double maximumSqr = maximum * maximum;
		for (Entity entity : mc().level.entitiesForRendering()) {
			Kind kind = kind(entity);
			double distanceSqr = mc().player.distanceToSqr(entity);
			boolean friend = entity instanceof Player && FriendManager.isFriend(entity.getUUID());
			if (distanceSqr > maximumSqr || !wanted(entity, kind, friend)) {
				continue;
			}
			cached.add(new Target(entity, kind, friend));
			if (cached.size() >= MAX_TARGETS) {
				break;
			}
		}
	}

	@Override
	protected void onDisable() {
		cached.clear();
		count = 0;
	}

	/** Called from the HUD extraction pass so entity interpolation follows render frames. */
	public void renderOverlay(GuiGraphicsExtractor graphics, float partialTick) {
		count = 0;
		if (!isEnabled() || cached.isEmpty() || mc().player == null) {
			return;
		}
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		float originX = guiWidth / 2.0f;
		float originY = origin.is("Crosshair") ? guiHeight / 2.0f : guiHeight;
		double maximum = range.get();
		double maximumSqr = maximum * maximum;
		float width = lineWidth.getFloat();

		for (Target selected : cached) {
			Entity entity = selected.entity();
			double distanceSqr = mc().player.distanceToSqr(entity);
			if (distanceSqr > maximumSqr || !wanted(entity, selected.kind(), selected.friend())
					|| (!throughWalls.get() && !mc().player.hasLineOfSight(entity))) {
				continue;
			}
			// Move the current hitbox by the entity's render interpolation delta. Using the live
			// box directly would leave the line updating at 20 Hz while the model moves smoothly.
			Vec3 rendered = entity.getPosition(partialTick);
			AABB box = entity.getBoundingBox().move(rendered.subtract(entity.position()));
			double x = (box.minX + box.maxX) * 0.5;
			double z = (box.minZ + box.maxZ) * 0.5;
			double y = switch (target.get()) {
				case "Head" -> box.maxY;
				case "Feet" -> box.minY;
				default -> (box.minY + box.maxY) * 0.5;
			};
			if (!Render3D.worldToScreen(x, y, z, guiWidth, guiHeight, targetProjection)) {
				continue;
			}
			int color = color(selected, distanceSqr, maximum);
			Render2D.line(graphics, originX, originY, (float) targetProjection[0],
					(float) targetProjection[1], width, color);
			if (stem.get()
					&& Render3D.worldToScreen(x, box.minY, z, guiWidth, guiHeight, feetProjection)
					&& Render3D.worldToScreen(x, box.maxY, z, guiWidth, guiHeight, headProjection)) {
				Render2D.line(graphics, (float) feetProjection[0], (float) feetProjection[1],
						(float) headProjection[0], (float) headProjection[1], width, color);
			}
			count++;
		}
	}

	private boolean wanted(Entity entity, Kind kind, boolean friend) {
		if (entity.isRemoved() || !entity.isAlive() || (!invisible.get() && entity.isInvisible())) {
			return false;
		}
		if (entity == mc().player) {
			return self.get() && mc().gameRenderer.mainCamera().isDetached();
		}
		if (entity instanceof Player player) {
			if (player.isSpectator()) {
				return false;
			}
			if (ignoreFriends.get() && friend) {
				return false;
			}
		}
		return switch (kind) {
			case PLAYER -> players.get();
			case HOSTILE -> hostiles.get() && hostileMobs.allows(entity.getType());
			case PASSIVE -> passives.get() && passiveMobs.allows(entity.getType());
			case ITEM -> items.get();
			case VEHICLE -> vehicles.get();
			case PROJECTILE -> projectiles.get();
			case OTHER -> others.get();
		};
	}

	private static Kind kind(Entity entity) {
		if (entity instanceof Player) return Kind.PLAYER;
		if (entity instanceof Mob mob) return mob instanceof Enemy ? Kind.HOSTILE : Kind.PASSIVE;
		if (entity instanceof ItemEntity) return Kind.ITEM;
		if (entity instanceof AbstractBoat || entity instanceof AbstractMinecart) return Kind.VEHICLE;
		if (entity instanceof Projectile) return Kind.PROJECTILE;
		return Kind.OTHER;
	}

	private int color(Target target, double distanceSqr, double maximum) {
		if (friendColors.get() && !ignoreFriends.get() && target.friend()) {
			return FriendManager.COLOR;
		}
		return switch (colorMode.get()) {
			case "Distance" -> ColorUtil.lerp(nearColor.get(), farColor.get(),
					(float) (Math.sqrt(distanceSqr) / Math.max(1.0, maximum)));
			case "Static" -> staticColor.get();
			case "Theme" -> Theme.accent1;
			default -> switch (target.kind()) {
				case PLAYER -> playerColor.get();
				case HOSTILE -> hostileColor.get();
				case PASSIVE -> passiveColor.get();
				case ITEM -> itemColor.get();
				case VEHICLE -> vehicleColor.get();
				case PROJECTILE -> projectileColor.get();
				case OTHER -> otherColor.get();
			};
		};
	}

	/** Number of tracers emitted in the latest rendered frame, useful in debug read-outs. */
	public int tracerCount() {
		return count;
	}
}
