package unlucky.utility.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only view of Litematica's loaded schematic, for the Printer.
 *
 * <p>Litematica is an optional dependency: it is on the compile classpath but may be
 * absent at runtime. Every method here answers safely when it is — {@link #present()}
 * is false, {@link #hasSchematic()} is false, {@link #required} returns null — so
 * callers never need their own guard.
 *
 * <p><b>Why the nested {@code Impl}:</b> a class is only loaded when first used, so
 * keeping every {@code fi.dy.masa} reference inside {@code Impl} means the JVM never
 * tries to resolve Litematica's classes unless {@link #PRESENT} let us call into it.
 * Putting those calls directly in this class's methods would risk verification of the
 * whole class — and a NoClassDefFoundError on a machine without the mod — the moment
 * anything touched {@code present()}.
 */
public final class LitematicaBridge {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("litematica");

	private LitematicaBridge() {
	}

	/** Whether Litematica is installed at all. */
	public static boolean present() {
		return PRESENT;
	}

	/**
	 * Whether a schematic is currently loaded and placed (a "ghost world" exists).
	 *
	 * <p><b>Only call this in-world.</b> Litematica builds its schematic world renderer
	 * lazily on the first ask, and that constructor needs the GPU device — during mod
	 * init it throws {@code IllegalStateException: Can't getDevice() before it was
	 * initialized}. Since config load re-enables saved modules at init time, anything
	 * a module does in {@code onEnable} must stick to {@link #present()}.
	 */
	public static boolean hasSchematic() {
		return PRESENT && Impl.schematicWorld() != null;
	}

	/**
	 * The state the schematic wants at {@code pos}, or null when there is no schematic
	 * or it holds nothing there. Air is returned as-is (callers skip it themselves) —
	 * null strictly means "no answer".
	 */
	public static BlockState required(BlockPos pos) {
		if (!PRESENT) {
			return null;
		}
		return Impl.required(pos);
	}

	/**
	 * Whether {@code pos} is inside Litematica's current render-layer view. Honouring
	 * this is what makes the printer follow the layer slider you already use to build
	 * up a schematic a layer at a time. Answers true when Litematica is absent so a
	 * missing layer range never silently filters everything out.
	 */
	public static boolean withinLayerRange(int x, int y, int z) {
		if (!PRESENT) {
			return true;
		}
		return Impl.withinLayerRange(x, y, z);
	}

	/** An inclusive block region. */
	public record Region(BlockPos min, BlockPos max) {
		public boolean contains(int x, int y, int z) {
			return x >= min.getX() && x <= max.getX()
					&& y >= min.getY() && y <= max.getY()
					&& z >= min.getZ() && z <= max.getZ();
		}
	}

	/**
	 * The region every enabled placement covers, or null when nothing is placed.
	 *
	 * <p>This is what gives automation a finite world to work in: the sweep only visits
	 * standing positions over this footprint, and movement is leashed to it so a stray
	 * path can never walk the player off across the map.
	 */
	public static Region bounds() {
		return bounds(null);
	}

	/**
	 * As {@link #bounds()}, but limited to the placement with this name.
	 *
	 * <p>Null (or a name nothing answers to) means every enabled placement, which is
	 * also what the printer's "All" picker sends. Scoping by name rather than by
	 * Litematica's <i>selected</i> placement is deliberate: selection is a UI cursor
	 * that moves whenever you click in Litematica's own list, and a printer that
	 * changed target under you mid-build would be its own kind of bug.
	 */
	public static Region bounds(String placementName) {
		if (!PRESENT) {
			return null;
		}
		return Impl.bounds(placementName);
	}

	/** Names of every enabled placement, in Litematica's own order. */
	public static java.util.List<String> placementNames() {
		if (!PRESENT) {
			return java.util.List.of();
		}
		return Impl.placementNames();
	}

	/**
	 * Litematica's layer-view settings, captured so the printer can put them back.
	 *
	 * <p>The mode is kept as its config string rather than the enum: naming
	 * {@code LayerMode} in a field here would drag a Litematica type into this class's
	 * own signature, which is exactly what the {@code Impl} split exists to avoid.
	 */
	public record LayerView(String mode, int single, int rangeMin, int rangeMax) {
	}

	/** The current layer-view settings, or null when Litematica is absent. */
	public static LayerView captureLayerView() {
		if (!PRESENT) {
			return null;
		}
		return Impl.captureLayerView();
	}

	/**
	 * Clamps Litematica's layer view to the inclusive Y band {@code minY..maxY}.
	 *
	 * <p>Driving the layer view rather than filtering privately is deliberate: the
	 * printer and Litematica's own overlay then agree about what is being worked on, so
	 * the ghost blocks on screen are exactly the band being built.
	 */
	public static void setLayerBand(int minY, int maxY) {
		if (PRESENT) {
			Impl.setLayerBand(minY, maxY);
		}
	}

	/** Puts a {@link #captureLayerView()} snapshot back. Null is ignored. */
	public static void restoreLayerView(LayerView view) {
		if (PRESENT && view != null) {
			Impl.restoreLayerView(view);
		}
	}

	/** Everything that names a Litematica type, loaded only once {@link #PRESENT} is known true. */
	private static final class Impl {
		private Impl() {
		}

		static fi.dy.masa.litematica.world.WorldSchematic schematicWorld() {
			return fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
		}

		static BlockState required(BlockPos pos) {
			fi.dy.masa.litematica.world.WorldSchematic world = schematicWorld();
			return world == null ? null : world.getBlockState(pos);
		}

		static boolean withinLayerRange(int x, int y, int z) {
			// LayerRange moved to fi.dy.masa.malilib.util.position in the 26.2 line;
			// it used to live in litematica.util, which is what older printers import.
			fi.dy.masa.malilib.util.position.LayerRange range =
					fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
			return range == null || range.isPositionWithinRange(x, y, z);
		}

		static java.util.List<String> placementNames() {
			fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager manager =
					fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
			if (manager == null) {
				return java.util.List.of();
			}
			java.util.List<String> names = new java.util.ArrayList<>();
			for (fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement
					: manager.getAllSchematicsPlacements()) {
				if (placement.isEnabled() && !names.contains(placement.getName())) {
					names.add(placement.getName());
				}
			}
			return names;
		}

		static Region bounds(String placementName) {
			fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager manager =
					fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
			if (manager == null) {
				return null;
			}
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			// every *enabled* placement, not just the selected one: the schematic world
			// holds them all, so that is the region the printer will find work in
			for (fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement
					: manager.getAllSchematicsPlacements()) {
				if (!placement.isEnabled()
						|| (placementName != null && !placementName.equals(placement.getName()))) {
					continue;
				}
				// Sub-region boxes, not getEclosingBox(): that one is a bare field read
				// whose only writer is private, so it stays null unless Litematica
				// happens to want it for rendering — with "render enclosing box" off it
				// is null forever, and the printer would think nothing was placed.
				// getSubRegionBoxes builds from the schematic's own area sizes every
				// call, so it is always there.
				for (fi.dy.masa.litematica.selection.Box box : placement.getSubRegionBoxes(
						fi.dy.masa.litematica.schematic.placement.SubRegionPlacement
								.RequiredEnabled.PLACEMENT_ENABLED).values()) {
					if (box.getPos1() == null || box.getPos2() == null) {
						continue;
					}
					BlockPos p1 = box.getPos1();
					BlockPos p2 = box.getPos2();
					minX = Math.min(minX, Math.min(p1.getX(), p2.getX()));
					minY = Math.min(minY, Math.min(p1.getY(), p2.getY()));
					minZ = Math.min(minZ, Math.min(p1.getZ(), p2.getZ()));
					maxX = Math.max(maxX, Math.max(p1.getX(), p2.getX()));
					maxY = Math.max(maxY, Math.max(p1.getY(), p2.getY()));
					maxZ = Math.max(maxZ, Math.max(p1.getZ(), p2.getZ()));
				}
			}
			if (minX == Integer.MAX_VALUE) {
				return null;
			}
			return new Region(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
		}

		static LayerView captureLayerView() {
			fi.dy.masa.malilib.util.position.LayerRange range =
					fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
			if (range == null) {
				return null;
			}
			return new LayerView(range.getLayerMode().getStringValue(), range.getLayerSingle(),
					range.getLayerRangeMin(), range.getLayerRangeMax());
		}

		static void setLayerBand(int minY, int maxY) {
			fi.dy.masa.malilib.util.position.LayerRange range =
					fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
			if (range == null) {
				return;
			}
			range.setLayerMode(fi.dy.masa.malilib.util.LayerMode.LAYER_RANGE);
			applyBand(range, Math.min(minY, maxY), Math.max(minY, maxY));
		}

		static void restoreLayerView(LayerView view) {
			fi.dy.masa.malilib.util.position.LayerRange range =
					fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
			if (range == null) {
				return;
			}
			applyBand(range, view.rangeMin(), view.rangeMax());
			range.setLayerSingle(view.single());
			fi.dy.masa.malilib.util.LayerMode mode =
					fi.dy.masa.malilib.util.LayerMode.fromStringStatic(view.mode());
			range.setLayerMode(mode == null ? fi.dy.masa.malilib.util.LayerMode.ALL : mode);
		}

		/**
		 * Writes both ends of the range, min first and then min again.
		 *
		 * <p>Each setter clamps against the other end ({@code setLayerRangeMin} does
		 * {@code Math.min(value, layerRangeMax)}), so a single pass loses the first write
		 * whenever the new band sits entirely above the old one. Min, max, min lands in
		 * both directions.
		 */
		private static void applyBand(fi.dy.masa.malilib.util.position.LayerRange range,
				int minY, int maxY) {
			range.setLayerRangeMin(minY);
			range.setLayerRangeMax(maxY);
			range.setLayerRangeMin(minY);
		}
	}
}
