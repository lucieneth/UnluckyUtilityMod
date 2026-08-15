package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.GroupSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.ToggleGroupSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldScan;

/**
 * Single owner of the ESP mask: decides what gets a silhouette, in what colour, and
 * how the post chain turns that mask into pixels.
 *
 * <p>The shape of this module — one module with a target list rather than a highlight
 * setting on every ESP module — follows how the established clients do it, and the four
 * knobs that actually control the look are the same in all of them. Meteor's outline UBO
 * is {@code {int width, float fillOpacity, int shapeMode, float glowMultiplier}}, and
 * Future's menu exposes LineWidth, Filled, Filled Blend and an Outline/Glow pair over
 * the same ideas. Their shipped defaults are much lighter than anything we had been
 * running: a one-pixel line, fill off, and a fill blend of 0.02 when it is on.
 *
 * <p>{@link unlucky.utility.client.util.EspGlow} consults this module for every entity
 * colour, and it is the only source — the per-module highlight layers it replaced have
 * been removed rather than left to compete. Container scanning lives here too, feeding
 * {@link unlucky.utility.client.util.StorageEspRenderer}; the old StorageESP module is
 * gone, and its wireframe ancestor is kept as {@code StorageESP.java.bak}. What survives
 * elsewhere is {@link PlayerESP}, and only as screen-space overlays — boxes, names,
 * health and armor bars, skeleton, tracers — none of which ever went through the mask.
 */
public class Shader extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Crisp border, or a soft falloff", "Outline", "Outline", "Glow"));
	public final NumberSetting lineWidth = add(new NumberSetting("Line width", "Border thickness in pixels", 1.0, 0.5, 4.0, 0.1));
	public final NumberSetting glowFalloff = add(new NumberSetting("Glow falloff", "How far the glow reaches", 2.5, 1.0, 6.0, 0.5), () -> mode.is("Glow"));
	public final BooleanSetting filled = add(new BooleanSetting("Filled", "Tint the silhouette's interior", false));
	public final NumberSetting fillBlend = add(new NumberSetting("Fill blend", "Interior tint strength", 0.02, 0.0, 0.5, 0.01), filled::get);
	public final NumberSetting renderDistance = add(new NumberSetting("Render distance", "Max distance (0 = unlimited)", 0, 0, 256, 8));

	public final GroupSetting targets = add(new GroupSetting("Targets", "What gets a silhouette"));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Your own body (visible in Freecam)", false), targets::isExpanded);
	public final BooleanSetting players = add(new BooleanSetting("Players", "Other players", true), targets::isExpanded);
	public final BooleanSetting monsters = add(new BooleanSetting("Monsters", "Hostile mobs", true), targets::isExpanded);
	public final BooleanSetting animals = add(new BooleanSetting("Animals", "Passive and neutral mobs", false), targets::isExpanded);
	public final BooleanSetting vehicles = add(new BooleanSetting("Vehicles", "Boats and minecarts", false), targets::isExpanded);
	public final BooleanSetting items = add(new BooleanSetting("Items", "Dropped items", false), targets::isExpanded);
	public final BooleanSetting pearls = add(new BooleanSetting("Pearls", "Thrown ender pearls", true), targets::isExpanded);
	public final BooleanSetting crystals = add(new BooleanSetting("Crystals", "End crystals", true), targets::isExpanded);
	public final BooleanSetting armorStands = add(new BooleanSetting("Armor stands", "Armor stands", false), targets::isExpanded);
	public final BooleanSetting others = add(new BooleanSetting("Others", "Item frames and anything else", false), targets::isExpanded);

	// Storage carries its own submenu: a dozen container types with individual colours
	// would swamp the list, and they are only ever adjusted together.
	public final ToggleGroupSetting storages = add(new ToggleGroupSetting("Storages", "Containers", true), targets::isExpanded);
	public final NumberSetting storageRange = add(new NumberSetting("Storage range", "Scan radius", 64, 16, 128, 8), this::storageOpen);
	public final BooleanSetting chests = add(new BooleanSetting("Chests", "Regular chests", true), this::storageOpen);
	public final ColorSetting chestColor = add(new ColorSetting("Chest color", "Chest silhouette color", 0xFFE8A33D), this::storageOpen);
	public final BooleanSetting trappedChests = add(new BooleanSetting("Trapped chests", "Trapped chests", true), this::storageOpen);
	public final ColorSetting trappedColor = add(new ColorSetting("Trapped color", "Trapped chest color", 0xFFE85C5C), this::storageOpen);
	public final BooleanSetting enderChests = add(new BooleanSetting("Ender chests", "Ender chests", true), this::storageOpen);
	public final ColorSetting enderColor = add(new ColorSetting("Ender color", "Ender chest color", 0xFFB65CFF), this::storageOpen);
	public final BooleanSetting shulkers = add(new BooleanSetting("Shulkers", "Shulker boxes", true), this::storageOpen);
	public final BooleanSetting shulkerDye = add(new BooleanSetting("Shulker dye", "Color shulkers by their dye", true), this::storageOpen);
	public final ColorSetting shulkerColor = add(new ColorSetting("Shulker color", "Fallback for uncolored shulkers", 0xFFFF7ED8), this::storageOpen);
	public final BooleanSetting barrels = add(new BooleanSetting("Barrels", "Barrels", true), this::storageOpen);
	public final ColorSetting barrelColor = add(new ColorSetting("Barrel color", "Barrel color", 0xFFC98F55), this::storageOpen);
	public final BooleanSetting hoppers = add(new BooleanSetting("Hoppers", "Hoppers", false), this::storageOpen);
	public final ColorSetting hopperColor = add(new ColorSetting("Hopper color", "Hopper color", 0xFFAAAAB4), this::storageOpen);
	public final BooleanSetting furnaces = add(new BooleanSetting("Furnaces", "Furnaces, smokers, blast furnaces", false), this::storageOpen);
	public final ColorSetting furnaceColor = add(new ColorSetting("Furnace color", "Furnace color", 0xFFFF8A5C), this::storageOpen);
	public final BooleanSetting minecarts = add(new BooleanSetting("Minecarts", "Chest/furnace/hopper minecarts", true), this::storageOpen);
	public final ColorSetting minecartColor = add(new ColorSetting("Minecart color", "Container minecart color", 0xFF9CE8A3), this::storageOpen);
	public final BooleanSetting storageTracers = add(new BooleanSetting("Storage tracers", "Line from the camera to each container", false), this::storageOpen);
	public final NumberSetting tracerWidth = add(new NumberSetting("Tracer width", "Width of storage tracer lines", 1.0, 0.5, 4.0, 0.1), () -> storageOpen() && storageTracers.get());
	public final BooleanSetting labels = add(new BooleanSetting("Labels", "Show the container type above each box", false), this::storageOpen);
	public final NumberSetting labelScale = add(new NumberSetting("Label scale", "Size of storage labels", 1.0, 0.5, 2.0, 0.1), () -> storageOpen() && labels.get());
	public final NumberSetting dimDistance = add(new NumberSetting("Dim distance", "Fade out when closer than this (0 = off)", 5, 0, 16, 1), this::storageOpen);
	public final NumberSetting minimumAlpha = add(new NumberSetting("Minimum alpha", "Drop containers below this fade alpha", 10, 0, 100, 1), this::storageOpen);

	public final GroupSetting colors = add(new GroupSetting("Colors", "Per-category silhouette colors"));
	public final ColorSetting playerColor = add(new ColorSetting("Player color", "Player silhouette color", 0xFF87B93D), colors::isExpanded);
	public final ColorSetting monsterColor = add(new ColorSetting("Monster color", "Hostile silhouette color", 0xFFFF5555), colors::isExpanded);
	public final ColorSetting animalColor = add(new ColorSetting("Animal color", "Animal silhouette color", 0xFF7EE787), colors::isExpanded);
	public final ColorSetting vehicleColor = add(new ColorSetting("Vehicle color", "Vehicle silhouette color", 0xFF9CE8A3), colors::isExpanded);
	public final ColorSetting itemColor = add(new ColorSetting("Item color", "Item silhouette color", 0xFFFFD966), colors::isExpanded);
	public final ColorSetting pearlColor = add(new ColorSetting("Pearl color", "Pearl silhouette color", 0xFFB65CFF), colors::isExpanded);
	public final ColorSetting crystalColor = add(new ColorSetting("Crystal color", "End crystal silhouette color", 0xFFFF7ED8), colors::isExpanded);
	public final ColorSetting otherColor = add(new ColorSetting("Other color", "Misc silhouette color", 0xFFAAAAB4), colors::isExpanded);

	// Colour normalisation. Future exposes this as Saturation/Lightness, and pinning both
	// is most of why its palette reads as one set: hue still separates the categories
	// while every silhouette lands at the same intensity against the world.
	public final GroupSetting palette = add(new GroupSetting("Palette", "Shared color treatment"));
	public final BooleanSetting normalize = add(new BooleanSetting("Normalize colors", "Force every color to a common saturation and lightness", false), palette::isExpanded);
	public final BooleanSetting rainbow = add(new BooleanSetting("Rainbow", "Cycle every silhouette through the spectrum", false), palette::isExpanded);
	public final NumberSetting rainbowSpeed = add(new NumberSetting("Rainbow speed", "Cycle speed", 1.0, 0.1, 5.0, 0.1), () -> palette.isExpanded() && rainbow.get());
	public final NumberSetting gradientScale = add(new NumberSetting("Gradient scale", "Hue cycles per 16 blocks (0 = every target the same hue)", 0.3, 0.0, 3.0, 0.1), () -> palette.isExpanded() && rainbow.get());
	// shared by both treatments: rainbow sweeps hue but still needs an intensity to sweep at
	public final NumberSetting saturation = add(new NumberSetting("Saturation", "Color intensity", 46, 0, 100, 1), () -> palette.isExpanded() && (normalize.get() || rainbow.get()));
	public final NumberSetting lightness = add(new NumberSetting("Lightness", "Color brightness", 97, 0, 100, 1), () -> palette.isExpanded() && (normalize.get() || rainbow.get()));

	/** One highlighted container: its shape, color and label. */
	public record Storage(AABB box, int color, BlockPos pos, String label) {
	}

	private final List<Storage> storageTargets = new ArrayList<>();
	private int ticksUntilScan;

	public Shader() {
		super("Shader", "Silhouette highlighting through the ESP mask", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	/** Whether the storage submenu is open — the visibility condition for its children. */
	private boolean storageOpen() {
		return targets.isExpanded() && storages.isExpanded();
	}

	@Override
	protected void onEnable() {
		storageTargets.clear();
		ticksUntilScan = 0;
	}

	@Override
	protected void onDisable() {
		storageTargets.clear();
	}

	/**
	 * Containers to feed into the mask, already faded. Read by the renderer on the render
	 * thread; the list is only mutated on the client tick, and both run on the main
	 * thread, so no copy is needed.
	 */
	public List<Storage> storageTargets() {
		return storageTargets;
	}

	@Override
	public void onTick() {
		if (!storages.get() || mc().level == null || mc().player == null) {
			storageTargets.clear();
			return;
		}
		if (--ticksUntilScan <= 0) {
			ticksUntilScan = 10;
			rescanStorage();
		}
		if (!storageTracers.get() && !labels.get()) {
			return;
		}
		// tracers and labels stay line/text gizmos — the mask only carries shapes
		Vec3 camera = mc().gameRenderer.mainCamera().position();
		for (Storage target : storageTargets) {
			if (storageTracers.get()) {
				Render3D.line(camera, target.box().getCenter(), target.color(), tracerWidth.getFloat(), true);
			}
			if (labels.get()) {
				Render3D.blockLabel(target.label(), target.pos(), target.color(), labelScale.getFloat());
			}
		}
	}

	private void rescanStorage() {
		storageTargets.clear();
		Vec3 eye = mc().player.getEyePosition();
		double dim = dimDistance.get();
		float floor = minimumAlpha.getFloat() / 100.0f;
		for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(storageRange.get())) {
			int color = storageColorFor(blockEntity);
			if (color == 0) {
				continue;
			}
			BlockPos pos = blockEntity.getBlockPos();
			// match the actual block outline (chests are smaller than a full block, and a
			// hopper is not a cube at all) so the silhouette reads as the container shape
			BlockState state = mc().level.getBlockState(pos);
			VoxelShape shape = state.getShape(mc().level, pos);
			AABB box = shape.isEmpty() ? new AABB(pos).deflate(0.03) : shape.bounds().move(pos);

			float alpha = 1.0f;
			if (dim > 0) {
				double distance = Math.sqrt(box.getCenter().distanceToSqr(eye));
				alpha = (float) Math.clamp(distance / dim, 0.0, 1.0);
			}
			if (alpha < floor) {
				continue;
			}
			storageTargets.add(new Storage(box, ColorUtil.multiplyAlpha(tint(color, box.getCenter()) | 0xFF000000, alpha),
					pos.immutable(), state.getBlock().getName().getString()));
		}
	}

	/** Highlight color for a container, or 0 when its type is disabled. */
	public int storageColorFor(BlockEntity blockEntity) {
		// trapped chests extend ChestBlockEntity, so test them first
		if (blockEntity instanceof TrappedChestBlockEntity) {
			return trappedChests.get() ? trappedColor.get() : 0;
		}
		if (blockEntity instanceof ShulkerBoxBlockEntity shulker) {
			if (!shulkers.get()) {
				return 0;
			}
			if (shulkerDye.get() && shulker.getColor() != null) {
				return shulker.getColor().getTextureDiffuseColor() | 0xFF000000;
			}
			return shulkerColor.get();
		}
		return switch (blockEntity) {
			case ChestBlockEntity ignored -> chests.get() ? chestColor.get() : 0;
			case EnderChestBlockEntity ignored -> enderChests.get() ? enderColor.get() : 0;
			case BarrelBlockEntity ignored -> barrels.get() ? barrelColor.get() : 0;
			case HopperBlockEntity ignored -> hoppers.get() ? hopperColor.get() : 0;
			case AbstractFurnaceBlockEntity ignored -> furnaces.get() ? furnaceColor.get() : 0;
			default -> 0;
		};
	}

	/** Border thickness the post chain should use, in texels. */
	public float widthTexels() {
		return mode.is("Glow") ? glowFalloff.getFloat() : lineWidth.getFloat();
	}

	/**
	 * Interior tint, 0 when "Filled" is off. Kept separate from the border so a
	 * border-only look — which is what every reference client ships as its default —
	 * costs nothing to express.
	 */
	public float fillOpacity() {
		return filled.get() ? fillBlend.getFloat() : 0.0f;
	}

	/** Silhouette color for an entity, or 0 when it is not a target. */
	public int colorFor(Entity entity) {
		if (!isEnabled()) {
			return 0;
		}
		if (renderDistance.get() > 0 && mc().player != null
				&& entity.distanceTo(mc().player) > renderDistance.get()) {
			return 0;
		}
		return tint(rawColorFor(entity), entity.position());
	}

	private int rawColorFor(Entity entity) {
		if (entity instanceof Player player) {
			if (player == mc().player) {
				return self.get() ? playerColor.get() : 0;
			}
			return players.get() ? playerColor.get() : 0;
		}
		if (entity instanceof ThrownEnderpearl) {
			return pearls.get() ? pearlColor.get() : 0;
		}
		if (entity instanceof ItemEntity item) {
			// ItemESP annotates this pass rather than owning one of its own: when its Silhouette
			// switch is on, the items it has filtered to get its colour here, so the outline and
			// the label it draws can never disagree about which drops matter. Its answer is 0
			// whenever the switch is off, which leaves the Items toggle below meaning exactly
			// what it always meant.
			int annotated = UnluckyClient.INSTANCE.modules.get(ItemESP.class).silhouetteColor(item);
			if (annotated != 0) {
				return annotated;
			}
			return items.get() ? itemColor.get() : 0;
		}
		if (entity instanceof AbstractBoat || entity instanceof AbstractMinecart) {
			return vehicles.get() ? vehicleColor.get() : 0;
		}
		if (entity instanceof Mob mob) {
			if (mob instanceof Enemy) {
				return monsters.get() ? monsterColor.get() : 0;
			}
			// neutral mobs sit with the animals; Future gives them their own toggle, but
			// the split only matters for a handful of species
			return animals.get() ? animalColor.get() : 0;
		}
		if (entity instanceof EndCrystal) {
			return crystals.get() ? crystalColor.get() : 0;
		}
		if (entity instanceof ArmorStand) {
			return armorStands.get() ? otherColor.get() : 0;
		}
		if (entity instanceof ItemFrame) {
			return others.get() ? otherColor.get() : 0;
		}
		return 0;
	}

	/**
	 * Applies the shared colour treatment. Rainbow wins over normalisation when both are
	 * on, since it is already producing a fully saturated hue sweep of its own.
	 *
	 * @param position where the target is, used to offset its place in the sweep; null
	 *                 gives every target the same hue at any instant
	 */
	public int tint(int argb, Vec3 position) {
		if (argb == 0) {
			return argb;
		}
		if (rainbow.get()) {
			// nanoTime, not currentTimeMillis: the wall clock only advances in ~15.6ms steps
			// on Windows, which is coarser than a frame and makes the sweep visibly stair-step
			// instead of scroll. Milliseconds stay a double so the division is continuous
			// rather than truncating to whole periods.
			double periodMillis = 10_000.0 / rainbowSpeed.getFloat();
			double phase = System.nanoTime() / 1_000_000.0 / periodMillis;
			// Offsetting by world position is what makes this read like an arraylist
			// rainbow rather than one synchronised pulse: neighbouring targets sit at
			// different points in the sweep, so the colour appears to travel through the
			// world instead of every silhouette changing in lockstep. Taken from the world
			// rather than the screen so a target keeps its hue as you turn the camera.
			if (position != null) {
				phase += (position.x + position.y + position.z) * gradientScale.getFloat() / 16.0;
			}
			// Java's % keeps the sign of the dividend, and world coordinates go negative
			float hue = (float) (((phase % 1.0) + 1.0) % 1.0);
			return ColorUtil.hsb(hue, saturation.getFloat() / 100.0f, lightness.getFloat() / 100.0f,
					(argb >>> 24) & 0xFF);
		}
		if (!normalize.get()) {
			return argb;
		}
		return ColorUtil.withSaturationLightness(argb, saturation.getFloat() / 100.0f,
				lightness.getFloat() / 100.0f);
	}
}
