package unlucky.utility.client.module.modules.render;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * The box around the block you are looking at, under your control.
 *
 * <p><b>One outline, drawn once.</b> Vanilla's selected-block outline is a single submission, and
 * this module either changes that submission or replaces it — never both. Drawing our own on top of
 * vanilla's is the obvious implementation and it is visibly wrong: two lines a fraction of a pixel
 * apart, z-fighting on every edge, and a "line width" setting that only thickens one of them.
 *
 * <p><b>Two drawing paths, and the reason is depth.</b> Everything expressible as a change to
 * vanilla's own submission — the shape, the colour, the width — goes through it, which keeps the
 * outline exactly as sharp and exactly as per-frame as it always was. The things vanilla's
 * submission cannot express — a filled body, an outline that shows through terrain, a marker on
 * the placement face, an outline around a fluid it never selected — are drawn as gizmos instead,
 * and gizmos are re-emitted per tick. That is up to one tick of lag on a box that snaps to whole
 * blocks anyway, and it is the honest cost of asking for something the vanilla pass does not do.
 *
 * <p><b>Show fluids never changes what you are pointing at.</b> It runs its own clip and draws the
 * result; {@code hitResult} stays exactly as vanilla computed it, because that value decides what
 * right-clicking does and a render module has no business editing it.
 */
public class BlockOutline extends Module {
	/** How far a face marker sits off the block, so it does not z-fight the face it marks. */
	private static final double FACE_OFFSET = 0.002;

	public final ModeSetting boxMode = add(new ModeSetting("Box mode",
			"Vanilla shape follows the block's real outline; Full cube squares off stairs and "
					+ "slabs; Selected face marks only the side you are pointing at",
			"Vanilla shape", "Vanilla shape", "Full cube", "Selected face"));
	public final ModeSetting shape = add(new ModeSetting("Shape",
			"Outline, a translucent body, or both", "Outline", "Outline", "Fill", "Both"));

	public final ModeSetting colorMode = add(new ModeSetting("Color mode",
			"Block color takes the block's own map colour", "Theme",
			"Static", "Theme", "Rainbow", "Block color"));
	public final ColorSetting sideColor = add(new ColorSetting("Side color",
			"Fill colour", 0x30B478FF), () -> !shape.is("Outline"));
	public final ColorSetting lineColor = add(new ColorSetting("Line color",
			"Outline colour", 0xFFB478FF), () -> !shape.is("Fill"));
	public final NumberSetting lineWidth = add(new NumberSetting("Line width",
			"Outline thickness", 1.5, 0.5, 5.0, 0.1), () -> !shape.is("Fill"));

	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Keep the outline visible through terrain. Costs the per-frame vanilla submission — "
					+ "see the class note.", false));
	public final BooleanSetting hideWhenInside = add(new BooleanSetting("Hide when inside",
			"Hide the outline when the camera is inside the block it selects", true));
	public final BooleanSetting showFluids = add(new BooleanSetting("Show fluids",
			"Also outline the fluid you are looking at. Display only — it never changes what "
					+ "right-clicking does.", false));
	public final BooleanSetting distanceFade = add(new BooleanSetting("Distance fade",
			"Fade the outline out toward the edge of your reach", true));
	public final BooleanSetting faceMarker = add(new BooleanSetting("Placement face marker",
			"Highlight the exact face a block would be placed against", false));

	public BlockOutline() {
		super("BlockOutline", "Restyles the selected-block outline", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	/**
	 * What the shared hit-outline submission should do this frame.
	 *
	 * @param draw  whether vanilla should submit at all
	 * @param shape the shape to submit, which may be the one it was going to use
	 */
	public record Decision(boolean draw, VoxelShape shape, int color, float width) {
	}

	/**
	 * Vanilla's one outline submission, answered for.
	 *
	 * <p>Called from {@code LevelRendererMixin} with what vanilla was about to draw. Returning
	 * {@code draw = false} does not mean "no outline" — it means "not this one", and every case
	 * that says so has already arranged for the gizmo path to draw instead.
	 */
	public Decision decide(BlockPos pos, VoxelShape vanillaShape, int vanillaColor, float vanillaWidth) {
		if (!isEnabled()) {
			return new Decision(true, vanillaShape, vanillaColor, vanillaWidth);
		}
		if (ownsDrawing() || !visible(pos)) {
			return new Decision(false, vanillaShape, vanillaColor, vanillaWidth);
		}
		return new Decision(true, shapeFor(pos, vanillaShape), tinted(lineColor.get(), pos),
				lineWidth.getFloat());
	}

	/**
	 * Whether this module is drawing the outline itself this frame.
	 *
	 * <p>Any one of these makes vanilla's submission the wrong tool, and once the gizmo path is
	 * drawing the outline it has to draw all of it — half through vanilla and half through gizmos
	 * would put the two halves a tick out of step with each other.
	 */
	private boolean ownsDrawing() {
		return throughWalls.get() || !shape.is("Outline") || faceMarker.get() || showFluids.get();
	}

	@Override
	public void onTick() {
		if (!isEnabled() || mc().level == null || mc().player == null) {
			return;
		}
		if (ownsDrawing()) {
			drawSelected();
		}
		if (showFluids.get()) {
			drawFluid();
		}
	}

	/** The gizmo path's version of the selected block, including the fill and the face marker. */
	private void drawSelected() {
		if (!(mc().hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		if (!visible(pos)) {
			return;
		}
		BlockState state = mc().level.getBlockState(pos);
		VoxelShape selected = shapeFor(pos, state.getShape(mc().level, pos));
		int line = shape.is("Fill") ? 0 : tinted(lineColor.get(), pos);
		int side = shape.is("Outline") ? 0 : tinted(sideColor.get(), pos);
		emit(selected, pos, line, side);

		if (faceMarker.get()) {
			// Drawn as its own filled quad rather than as part of the box: the point of the
			// marker is which side, and a side you can only tell apart by looking for a missing
			// edge is not an answer.
			emit(faceShape(hit.getDirection()), pos, 0, tinted(lineColor.get(), pos));
		}
	}

	/**
	 * The fluid outline.
	 *
	 * <p>A second clip of our own with fluids included. Vanilla's hit result excluded them by
	 * design and stays excluded — this only ever adds something to look at.
	 */
	private void drawFluid() {
		Vec3 eye = mc().player.getEyePosition();
		Vec3 end = eye.add(mc().player.getLookAngle().scale(mc().player.blockInteractionRange()));
		HitResult fluid = mc().level.clip(new net.minecraft.world.level.ClipContext(eye, end,
				net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.ANY, mc().player));
		if (!(fluid instanceof BlockHitResult hit) || fluid.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		if (mc().level.getFluidState(pos).isEmpty() || !visible(pos)) {
			return;
		}
		VoxelShape fluidShape = mc().level.getFluidState(pos).getShape(mc().level, pos);
		if (fluidShape.isEmpty()) {
			return;
		}
		emit(fluidShape, pos, tinted(lineColor.get(), pos), 0);
	}

	/**
	 * Draws one shape as gizmos.
	 *
	 * <p>{@code toAabbs} rather than {@code bounds}: a fence, a wall, a chest with a lid are
	 * several boxes, and the bounding box of those is a cube that touches nothing. Multipart
	 * shapes are exactly the case "Vanilla shape" exists to keep.
	 */
	private void emit(VoxelShape shape, BlockPos pos, int line, int side) {
		if (shape.isEmpty() || (line == 0 && side == 0)) {
			return;
		}
		List<AABB> parts = shape.toAabbs();
		for (AABB part : parts) {
			Render3D.box(part.move(pos.getX(), pos.getY(), pos.getZ()),
					line, lineWidth.getFloat(), side, throughWalls.get());
		}
	}

	/** The shape to outline, per Box mode. */
	private VoxelShape shapeFor(BlockPos pos, VoxelShape vanillaShape) {
		return switch (boxMode.get()) {
			case "Full cube" -> Shapes.block();
			case "Selected face" -> mc().hitResult instanceof BlockHitResult hit
					&& hit.getBlockPos().equals(pos)
							? faceShape(hit.getDirection())
							: vanillaShape;
			default -> vanillaShape;
		};
	}

	/** A paper-thin slab on one face of the unit cube, lifted clear of the block surface. */
	private static VoxelShape faceShape(Direction face) {
		double low = -FACE_OFFSET;
		double high = 1.0 + FACE_OFFSET;
		return switch (face) {
			case DOWN -> Shapes.box(0.0, low, 0.0, 1.0, low, 1.0);
			case UP -> Shapes.box(0.0, high, 0.0, 1.0, high, 1.0);
			case NORTH -> Shapes.box(0.0, 0.0, low, 1.0, 1.0, low);
			case SOUTH -> Shapes.box(0.0, 0.0, high, 1.0, 1.0, high);
			case WEST -> Shapes.box(low, 0.0, 0.0, low, 1.0, 1.0);
			case EAST -> Shapes.box(high, 0.0, 0.0, high, 1.0, 1.0);
		};
	}

	/**
	 * Whether the outline should be drawn at all.
	 *
	 * <p>Hide when inside is not cosmetic: standing in a cobweb or a crop leaves a full-size box
	 * drawn around the camera, so the outline fills the screen with lines from the inside.
	 */
	private boolean visible(BlockPos pos) {
		if (!hideWhenInside.get()) {
			return true;
		}
		Vec3 camera = mc().gameRenderer.mainCamera().position();
		return !new AABB(pos).contains(camera);
	}

	/** Colour for this frame, with the distance fade applied. */
	private int tinted(int base, BlockPos pos) {
		int color = switch (colorMode.get()) {
			case "Rainbow" -> ColorUtil.withAlpha(
					ColorUtil.hsb(((System.currentTimeMillis() / 20L) % 360L) / 360.0f, 0.7f, 1.0f, 255),
					ColorUtil.alpha(base));
			case "Block color" -> {
				int rgb = mc().level.getBlockState(pos).getMapColor(mc().level, pos).col;
				yield rgb == 0 ? base : ColorUtil.withAlpha(0xFF000000 | rgb, ColorUtil.alpha(base));
			}
			default -> base;
		};
		if (!distanceFade.get() || mc().player == null) {
			return color;
		}
		double reach = Math.max(1.0, mc().player.blockInteractionRange());
		double distance = Vec3.atCenterOf(pos).distanceTo(mc().gameRenderer.mainCamera().position());
		float fade = (float) Mth.clamp(1.0 - (distance / reach - 0.6) / 0.4, 0.15, 1.0);
		return ColorUtil.multiplyAlpha(color, fade);
	}
}
