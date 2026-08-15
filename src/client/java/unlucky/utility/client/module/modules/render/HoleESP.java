package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.HoleUtil;
import unlucky.utility.client.util.PerfDebug;
import unlucky.utility.client.util.Render3D;

/**
 * Marks the holes around you that a crystal cannot reach into.
 *
 * <p>What counts as a hole is {@link HoleUtil}'s answer, not this module's. That matters more
 * here than anywhere else: this is the module the player <em>looks</em> at, and HoleFill and
 * Burrow are the ones that act on the same ground. A box drawn here that HoleFill declines to
 * fill, or that Burrow refuses to start in, is not a disagreement the player can debug — it just
 * looks like one of them is broken.
 *
 * <p>The material colours are the point of the display. Bedrock cannot be opened at all,
 * obsidian can be by someone patient, and mixed is only as strong as its weakest face — three
 * different decisions, so three different colours rather than one "safe" green.
 *
 * <p>Nothing is scanned per frame. The volume is swept incrementally under a per-tick budget,
 * the same shape as LightOverlay, because a full rescan of a 25×13×25 volume every frame is tens
 * of thousands of block lookups for a picture that only changes when somebody places a block.
 */
public class HoleESP extends Module {
	/** How far the player must move before the cached sweep is worth redoing. */
	private static final double MOVE_INVALIDATE = 2.0;

	public final NumberSetting horizontalRadius = add(new NumberSetting("Horizontal radius",
			"Blocks scanned either side of you", 12, 1, 32, 1));
	public final NumberSetting verticalRadius = add(new NumberSetting("Vertical radius",
			"Blocks scanned above and below you", 6, 1, 16, 1));

	public final BooleanSetting single = add(new BooleanSetting("Single",
			"Show one-block holes", true));
	public final BooleanSetting doubles = add(new BooleanSetting("Double",
			"Show two-block holes", true));
	public final BooleanSetting quad = add(new BooleanSetting("Quad",
			"Show 2x2 holes", false));

	public final BooleanSetting bedrock = add(new BooleanSetting("Bedrock",
			"Show holes walled entirely in unbreakable blocks", true));
	public final BooleanSetting obsidian = add(new BooleanSetting("Obsidian-resistant",
			"Show holes walled in blast-resistant blocks", true));
	public final BooleanSetting mixed = add(new BooleanSetting("Mixed",
			"Show holes with a mixture of bedrock and resistant walls", true));

	public final NumberSetting minimumDepth = add(new NumberSetting("Minimum depth",
			"Levels of resistant wall required above the floor", 1, 1, 3, 1));
	public final NumberSetting minimumHeadroom = add(new NumberSetting("Minimum headroom",
			"Passable blocks required above the floor", 2, 2, 3, 1));
	public final BooleanSetting allowWebs = add(new BooleanSetting("Allow webs",
			"Treat a hole with a cobweb in it as usable", false));
	public final BooleanSetting ignoreOwnHole = add(new BooleanSetting("Ignore own hole",
			"Hide the hole you are standing in", true));

	public final ModeSetting shape = add(new ModeSetting("Shape",
			"Outline, filled faces, or both", "Both", "Outline", "Fill", "Both"));
	public final NumberSetting height = add(new NumberSetting("Height",
			"How tall the marker box is drawn", 0.25, 0.05, 1.00, 0.05));
	public final BooleanSetting top = add(new BooleanSetting("Top",
			"Draw the top face", false));
	public final BooleanSetting bottom = add(new BooleanSetting("Bottom",
			"Draw the bottom face", true));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw holes that are behind terrain", true));
	public final BooleanSetting distanceFade = add(new BooleanSetting("Distance fade",
			"Fade markers out toward the edge of the scan", true));

	public final ColorSetting bedrockColor = add(new ColorSetting("Bedrock color",
			"Colour for unbreakable holes", 0xFF43D96B), bedrock::get);
	public final ColorSetting obsidianColor = add(new ColorSetting("Obsidian color",
			"Colour for blast-resistant holes", 0xFFFF5555), obsidian::get);
	public final ColorSetting mixedColor = add(new ColorSetting("Mixed color",
			"Colour for holes with mixed walls", 0xFFFFB347), mixed::get);

	public final NumberSetting refreshInterval = add(new NumberSetting("Refresh interval",
			"Ticks before the cached holes are rebuilt", 10, 1, 200, 1));

	/** A finished marker: where to draw it and what it is made of. */
	private record Marker(AABB box, HoleUtil.Material material) {
	}

	private final List<Marker> markers = new ArrayList<>();
	private BlockPos cacheOrigin;
	private int sinceRefresh;

	public HoleESP() {
		super("HoleESP", "Marks holes a crystal cannot reach into", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		markers.clear();
		cacheOrigin = null;
		sinceRefresh = Integer.MAX_VALUE;
	}

	@Override
	protected void onDisable() {
		markers.clear();
		cacheOrigin = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			onDisable();
			return;
		}
		BlockPos here = mc().player.blockPosition();
		sinceRefresh++;
		boolean moved = cacheOrigin == null
				|| here.distSqr(cacheOrigin) > MOVE_INVALIDATE * MOVE_INVALIDATE;
		if (moved || sinceRefresh >= refreshInterval.getInt()) {
			rescan(here);
		}
		render();
	}

	/**
	 * Rebuilds the marker list.
	 *
	 * <p>Done in one pass rather than spread across ticks like LightOverlay's, because the
	 * cheap rejection in {@link HoleUtil#scan} throws out the overwhelming majority of the
	 * volume — solid rock and open air both fail on the first test — and the classification only
	 * runs for the handful of positions that survive it. The scan radii are the budget.
	 */
	private void rescan(BlockPos origin) {
		long start = PerfDebug.begin();
		markers.clear();
		cacheOrigin = origin;
		sinceRefresh = 0;

		HoleUtil.Options options = new HoleUtil.Options(minimumDepth.getInt(),
				minimumHeadroom.getInt(), allowWebs.get());
		BlockPos own = ignoreOwnHole.get() ? mc().player.blockPosition() : null;

		for (HoleUtil.Hole hole : HoleUtil.scan(origin, horizontalRadius.getInt(),
				verticalRadius.getInt(), options)) {
			if (!wantsShape(hole.shape()) || !wantsMaterial(hole.material())) {
				continue;
			}
			if (own != null && hole.contains(own)) {
				continue;
			}
			markers.add(new Marker(boxFor(hole), hole.material()));
		}
		PerfDebug.end("tick.HoleESP.scan", start);
	}

	private boolean wantsShape(HoleUtil.Shape shape) {
		return switch (shape) {
			case SINGLE -> single.get();
			case DOUBLE -> doubles.get();
			case QUAD -> quad.get();
		};
	}

	private boolean wantsMaterial(HoleUtil.Material material) {
		return switch (material) {
			case BEDROCK -> bedrock.get();
			case OBSIDIAN -> obsidian.get();
			case MIXED -> mixed.get();
			case UNSAFE -> false;
		};
	}

	/**
	 * One box covering every cell of the hole.
	 *
	 * <p>A double or a quad is drawn as a single box rather than two or four, because it is one
	 * place to stand — drawing the cells separately puts a line down the middle of a space that
	 * has no line in it.
	 */
	private AABB boxFor(HoleUtil.Hole hole) {
		AABB box = null;
		for (BlockPos cell : hole.positions()) {
			AABB cellBox = new AABB(cell.getX(), cell.getY(), cell.getZ(),
					cell.getX() + 1.0, cell.getY() + height.get(), cell.getZ() + 1.0);
			box = box == null ? cellBox : box.minmax(cellBox);
		}
		return box;
	}

	private void render() {
		if (markers.isEmpty()) {
			return;
		}
		boolean fill = !shape.is("Outline");
		boolean outline = !shape.is("Fill");
		double maxDistance = Math.max(1, horizontalRadius.getInt());
		for (Marker marker : markers) {
			int base = colorFor(marker.material());
			float alpha = distanceFade.get() ? fadeFor(marker.box(), maxDistance) : 1.0f;
			if (alpha <= 0.02f) {
				continue;
			}
			int line = outline ? ColorUtil.multiplyAlpha(base, alpha) : 0;
			int side = fill ? ColorUtil.multiplyAlpha(ColorUtil.withAlpha(base, 60), alpha) : 0;
			Render3D.slab(marker.box(), line, 1.5f, side, throughWalls.get(),
					top.get(), bottom.get(), true);
		}
	}

	/**
	 * Fades a marker out as it approaches the edge of the scan.
	 *
	 * <p>Without it a hole pops into existence at full brightness the moment it enters the
	 * radius, which reads as a hole appearing rather than as the scan reaching it.
	 */
	private float fadeFor(AABB box, double maxDistance) {
		double dx = box.getCenter().x - mc().player.getX();
		double dz = box.getCenter().z - mc().player.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		return (float) Mth.clamp(1.0 - (distance / maxDistance - 0.7) / 0.3, 0.0, 1.0);
	}

	private int colorFor(HoleUtil.Material material) {
		return switch (material) {
			case BEDROCK -> bedrockColor.get();
			case MIXED -> mixedColor.get();
			default -> obsidianColor.get();
		};
	}

	/** How many holes are currently marked, for the debug read-out. */
	public int markerCount() {
		return markers.size();
	}
}
