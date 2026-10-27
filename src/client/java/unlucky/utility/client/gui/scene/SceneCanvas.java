package unlucky.utility.client.gui.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3x2f;
import org.joml.Vector3fc;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * The ChestManager scene's renderer as a reusable piece: a software orthographic camera over a
 * GUI rectangle, real block models and flat-coloured boxes painter-sorted into GUI quads, hull
 * outlines on top, and orbit/pan/zoom input.
 *
 * <p>GUI elements have no depth buffer, so everything goes into {@link #prims} with a sort key —
 * the depth of the cell it belongs to, then of the part within the cell — and {@link #draw}
 * sorts and submits them far to near. Overlays (outlines, highlights) are drawn after, unsorted.
 *
 * <p>A user calls {@link #begin} with the viewport and the radius of what should fit, adds
 * geometry, then calls {@link #draw}. Positions are in world units relative to whatever origin
 * the caller likes; {@link #focusX} and friends are in the same units.
 */
public final class SceneCanvas {
	public static final float PX = 1.0f / 16.0f;
	private static final int[][] BOX_FACES = {{0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 3, 2},
			{4, 5, 7, 6}};
	private static final Direction[] BOX_SIDES = {Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST,
			Direction.NORTH, Direction.SOUTH};

	public float yaw = 35.0f;
	public float pitch = 32.0f;
	public double focusX, focusY, focusZ;
	public float zoom = 1.0f;
	public float minZoom = 0.5f, maxZoom = 4.0f;

	private int dragButton = -1;
	private double lastX, lastY, pressX, pressY;
	private boolean moved;
	private int x, y, width, height;

	// per-frame camera
	private double rx, rz, ux, uy, uz, ex, ey, ez;
	private float scale, originX, originY;

	private final List<Prim> prims = new ArrayList<>();
	private final List<Prim> overlay = new ArrayList<>();
	private final Map<BlockState, List<BlockStateModelPart>> models = new HashMap<>();
	private TextureSetup atlas;

	private record Prim(float[] xy, float[] uv, boolean textured, int color, double cell, double part) {
	}

	/** Puts the camera back where a fresh view starts, looking at {@code (fx, fy, fz)}. */
	public void reset(double fx, double fy, double fz) {
		yaw = 35.0f;
		pitch = 32.0f;
		focusX = fx;
		focusY = fy;
		focusZ = fz;
		zoom = 1.0f;
		dragButton = -1;
	}

	// ------------------------------------------------------------------ camera

	/**
	 * Starts a frame over {@code (x, y, width, height)}: everything within {@code radius} of the
	 * focus fits at zoom 1.
	 */
	public void begin(int x, int y, int width, int height, double radius) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		prims.clear();
		overlay.clear();
		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		double sy = Math.sin(yr), cy = Math.cos(yr), sp = Math.sin(pr), cp = Math.cos(pr);
		rx = cy;
		rz = -sy;
		ux = -sy * sp;
		uy = cp;
		uz = -cy * sp;
		ex = sy * cp;
		ey = sp;
		ez = cy * cp;
		scale = (float) (Math.min(width, height) / (2.0 * Math.max(radius, 0.5)) * zoom);
		originX = x + width / 2.0f;
		originY = y + height / 2.0f;
	}

	public float screenX(double px, double pz) {
		return originX + (float) ((px - focusX) * rx + (pz - focusZ) * rz) * scale;
	}

	public float screenY(double px, double py, double pz) {
		return originY - (float) ((px - focusX) * ux + (py - focusY) * uy + (pz - focusZ) * uz) * scale;
	}

	/** Larger is nearer the viewer. */
	public double depth(double px, double py, double pz) {
		return (px - focusX) * ex + (py - focusY) * ey + (pz - focusZ) * ez;
	}

	/** Whether a face pointing this way is turned toward the viewer. */
	public boolean facing(Direction side) {
		return side.getStepX() * ex + side.getStepY() * ey + side.getStepZ() * ez > 1.0e-4;
	}

	public float scale() {
		return scale;
	}

	/**
	 * Which world axis a screen drag of {@code (dx, dy)} pixels follows best, and how many blocks
	 * along it: {@code {axis, blocks}}, axis 0/1/2 for x/y/z. Axes seen end-on are skipped.
	 */
	public int[] dragAlongAxis(double dx, double dy) {
		double[][] axes = {{rx * scale, -ux * scale}, {0, -uy * scale}, {rz * scale, -uz * scale}};
		int best = -1;
		double bestFit = 0, bestAmount = 0;
		for (int axis = 0; axis < 3; axis++) {
			double sx = axes[axis][0], sy = axes[axis][1];
			double length = Math.sqrt(sx * sx + sy * sy);
			if (length < 4) {
				continue;
			}
			double along = (dx * sx + dy * sy) / length;
			if (Math.abs(along) > bestFit) {
				bestFit = Math.abs(along);
				best = axis;
				bestAmount = along / length;
			}
		}
		return best < 0 ? new int[] {0, 0} : new int[] {best, (int) Math.round(bestAmount)};
	}

	// ------------------------------------------------------------------ geometry

	/**
	 * A block from its baked model with its corner at {@code (ox, oy, oz)}, skipping the sides
	 * {@code culled} says are covered. {@code tint} multiplies every face (white for none).
	 */
	public void block(BlockState state, double ox, double oy, double oz, int alpha, int tint,
			Predicate<Direction> culled) {
		List<BlockStateModelPart> parts = models.computeIfAbsent(state, key -> {
			List<BlockStateModelPart> collected = new ArrayList<>();
			Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(key)
					.collectParts(RandomSource.create(42L), collected);
			return collected;
		});
		double cell = depth(ox + 0.5, oy + 0.5, oz + 0.5);
		for (BlockStateModelPart part : parts) {
			for (Direction side : Direction.values()) {
				if (facing(side) && !culled.test(side)) {
					quads(state, part.getQuads(side), ox, oy, oz, alpha, tint, cell);
				}
			}
			quads(state, part.getQuads(null), ox, oy, oz, alpha, tint, cell);
		}
	}

	private void quads(BlockState state, List<BakedQuad> quads, double ox, double oy, double oz, int alpha,
			int tint, double cell) {
		for (BakedQuad quad : quads) {
			Direction facing = quad.direction();
			if (!facing(facing)) {
				continue;
			}
			float[] xy = new float[8];
			float[] uv = new float[8];
			double part = 0;
			for (int i = 0; i < 4; i++) {
				Vector3fc p = quad.position(i);
				double wx = ox + p.x(), wy = oy + p.y(), wz = oz + p.z();
				xy[i * 2] = screenX(wx, wz);
				xy[i * 2 + 1] = screenY(wx, wy, wz);
				part += depth(wx, wy, wz) / 4;
				long packed = quad.packedUV(i);
				uv[i * 2] = UVPair.unpackU(packed);
				uv[i * 2 + 1] = UVPair.unpackV(packed);
			}
			int color = tint;
			if (quad.materialInfo().isTinted()) {
				color = multiply(color, blockTint(state, quad.materialInfo().tintIndex()));
			}
			float shade = shade(facing.getStepX(), facing.getStepY(), facing.getStepZ());
			prims.add(new Prim(xy, uv, true, shaded(color, shade, alpha), cell, part));
		}
	}

	private static int blockTint(BlockState state, int index) {
		BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, index);
		return source == null ? 0xFFFFFFFF : 0xFF000000 | source.color(state);
	}

	/**
	 * A flat-coloured box, sorted inside the cell whose depth is {@code cell} (pass
	 * {@link Double#NaN} to sort by the box's own centre).
	 */
	public void box(double x0, double y0, double z0, double x1, double y1, double z1, int color, double cell) {
		double centre = depth((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
		double sortCell = Double.isNaN(cell) ? centre : cell;
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = new double[] {(i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1};
		}
		int alpha = (color >>> 24) & 0xFF;
		for (int f = 0; f < 6; f++) {
			Direction side = BOX_SIDES[f];
			if (!facing(side)) {
				continue;
			}
			float[] xy = new float[8];
			for (int i = 0; i < 4; i++) {
				double[] p = c[BOX_FACES[f][i]];
				xy[i * 2] = screenX(p[0], p[2]);
				xy[i * 2 + 1] = screenY(p[0], p[1], p[2]);
			}
			prims.add(new Prim(xy, null, false,
					shaded(color, shade(side.getStepX(), side.getStepY(), side.getStepZ()), alpha), sortCell, centre));
		}
	}

	/** The screen outline of a box, for picking and highlights. */
	public List<float[]> hull(double x0, double y0, double z0, double x1, double y1, double z1) {
		float[][] corners = new float[8][];
		for (int i = 0; i < 8; i++) {
			double px = (i & 1) == 0 ? x0 : x1, py = (i & 2) == 0 ? y0 : y1, pz = (i & 4) == 0 ? z0 : z1;
			corners[i] = new float[] {screenX(px, pz), screenY(px, py, pz)};
		}
		return hull(corners);
	}

	/** Every edge of a box as overlay lines: a wireframe that does not hide what is inside it. */
	public void wireframe(double x0, double y0, double z0, double x1, double y1, double z1, int color, float lineWidth) {
		double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
		for (int a = 0; a < 2; a++) {
			for (int b = 0; b < 2; b++) {
				line(x0, ys[a], zs[b], x1, ys[a], zs[b], color, lineWidth);
				line(xs[a], y0, zs[b], xs[a], y1, zs[b], color, lineWidth);
				line(xs[a], ys[b], z0, xs[a], ys[b], z1, color, lineWidth);
			}
		}
	}

	public void line(double ax, double ay, double az, double bx, double by, double bz, int color, float lineWidth) {
		float[] xy = strip(screenX(ax, az), screenY(ax, ay, az), screenX(bx, bz), screenY(bx, by, bz), lineWidth);
		if (xy != null) {
			overlay.add(new Prim(xy, null, false, color, 0, 0));
		}
	}

	public void fill(List<float[]> hull, int color) {
		for (int i = 1; i + 1 < hull.size(); i++) {
			float[] a = hull.getFirst(), b = hull.get(i), c = hull.get(i + 1);
			overlay.add(new Prim(new float[] {a[0], a[1], b[0], b[1], c[0], c[1], c[0], c[1]}, null, false, color, 0, 0));
		}
	}

	public void outline(List<float[]> hull, int color, float lineWidth) {
		for (int i = 0; i < hull.size(); i++) {
			float[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
			float[] xy = strip(a[0], a[1], b[0], b[1], lineWidth);
			if (xy != null) {
				overlay.add(new Prim(xy, null, false, color, 0, 0));
			}
		}
	}

	public static float shade(double nx, double ny, double nz) {
		return (float) (Math.abs(nx) * 0.6 + Math.abs(nz) * 0.8 + (ny > 0 ? ny : -ny * 0.5));
	}

	private static int shaded(int tint, float shade, int alpha) {
		shade = Math.min(1.0f, shade);
		return ColorUtil.argb(alpha, (int) (((tint >> 16) & 0xFF) * shade), (int) (((tint >> 8) & 0xFF) * shade),
				(int) ((tint & 0xFF) * shade));
	}

	private static int multiply(int a, int b) {
		return ColorUtil.argb(((a >>> 24) & 0xFF) * ((b >>> 24) & 0xFF) / 255,
				((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255, ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255,
				(a & 0xFF) * (b & 0xFF) / 255);
	}

	private static float[] strip(float ax, float ay, float bx, float by, float width) {
		float dx = bx - ax, dy = by - ay;
		float length = Mth.sqrt(dx * dx + dy * dy);
		if (length < 1.0e-3f) {
			return null;
		}
		float half = Math.max(0.5f, width * 0.5f);
		float nx = -dy / length * half, ny = dx / length * half;
		return new float[] {ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny};
	}

	/** Andrew's monotone chain; counter-clockwise on screen. */
	public static List<float[]> hull(float[][] points) {
		float[][] sorted = points.clone();
		java.util.Arrays.sort(sorted, (a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
		List<float[]> hull = new ArrayList<>();
		for (int pass = 0; pass < 2; pass++) {
			int start = hull.size();
			for (int k = 0; k < sorted.length; k++) {
				float[] p = sorted[pass == 0 ? k : sorted.length - 1 - k];
				while (hull.size() >= start + 2) {
					float[] a = hull.get(hull.size() - 2), b = hull.getLast();
					if ((b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) > 0) {
						break;
					}
					hull.removeLast();
				}
				hull.add(p);
			}
			hull.removeLast();
		}
		return hull;
	}

	public static boolean inside(List<float[]> polygon, double px, double py) {
		if (polygon.size() < 3) {
			return false;
		}
		boolean positive = false, negative = false;
		for (int i = 0; i < polygon.size(); i++) {
			float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
			double cross = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
			positive |= cross > 0;
			negative |= cross < 0;
		}
		return !(positive && negative);
	}

	// ------------------------------------------------------------------ submitting

	/** Sorts the scene far to near and submits it, then the overlay, clipped to the viewport. */
	public void draw(GuiGraphicsExtractor g) {
		prims.sort((a, b) -> a.cell() != b.cell() ? Double.compare(a.cell(), b.cell()) : Double.compare(a.part(), b.part()));
		g.enableScissor(x, y, x + width, y + height);
		submit(g, prims);
		submit(g, overlay);
		g.disableScissor();
	}

	/** Consecutive primitives on the same texture go out as one element, in order. */
	private void submit(GuiGraphicsExtractor g, List<Prim> list) {
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle area = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
		if (bounds == null || list.isEmpty()) {
			return;
		}
		int start = 0;
		for (int i = 1; i <= list.size(); i++) {
			if (i == list.size() || list.get(i).textured() != list.get(start).textured()) {
				boolean textured = list.get(start).textured();
				g.guiRenderState.addGuiElement(new Batch(
						textured ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI,
						textured ? atlas() : TextureSetup.noTexture(),
						pose, List.copyOf(list.subList(start, i)), scissor, bounds));
				start = i;
			}
		}
	}

	private TextureSetup atlas() {
		if (atlas == null) {
			AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			atlas = TextureSetup.singleTexture(loaded.getTextureView(), loaded.getSampler());
		}
		return atlas;
	}

	private record Batch(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, List<Prim> quads,
			ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer consumer) {
			for (Prim quad : quads) {
				// wind every quad like GuiGraphics.fill does, so culling never eats a turned face
				float area = 0;
				for (int i = 0; i < 4; i++) {
					int j = (i + 1) % 4;
					area += quad.xy()[i * 2] * quad.xy()[j * 2 + 1] - quad.xy()[j * 2] * quad.xy()[i * 2 + 1];
				}
				for (int n = 0; n < 4; n++) {
					int i = area > 0 ? 3 - n : n;
					VertexConsumer vertex = consumer.addVertexWith2DPose(pose, quad.xy()[i * 2], quad.xy()[i * 2 + 1]);
					if (quad.uv() != null) {
						vertex = vertex.setUv(quad.uv()[i * 2], quad.uv()[i * 2 + 1]);
					}
					vertex.setColor(quad.color());
				}
			}
		}
	}

	// ------------------------------------------------------------------ input

	public boolean contains(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x, y, width, height);
	}

	/** Starts an orbit (left) or pan (right) press inside the viewport. */
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY) || (button != InputConstants.MOUSE_BUTTON_LEFT
				&& button != InputConstants.MOUSE_BUTTON_RIGHT)) {
			return false;
		}
		dragButton = button;
		lastX = pressX = mouseX;
		lastY = pressY = mouseY;
		moved = false;
		return true;
	}

	public boolean dragging() {
		return dragButton >= 0;
	}

	/** Whether the current press has turned into a drag rather than a click. */
	public boolean moved() {
		return moved;
	}

	public double pressX() {
		return pressX;
	}

	public double pressY() {
		return pressY;
	}

	public void mouseDragged(double mouseX, double mouseY) {
		moved |= Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) >= 4;
		if (!moved) {
			return;
		}
		if (dragButton == InputConstants.MOUSE_BUTTON_LEFT) {
			yaw = Mth.wrapDegrees(yaw + (float) (mouseX - lastX) * 1.2f);
			pitch = Mth.clamp(pitch + (float) (mouseY - lastY) * 0.6f, -60.0f, 89.0f);
		} else if (dragButton == InputConstants.MOUSE_BUTTON_RIGHT && scale > 0) {
			double dx = (mouseX - lastX) / scale, dy = (mouseY - lastY) / scale;
			focusX -= dx * rx - dy * ux;
			focusY -= -dy * uy;
			focusZ -= dx * rz - dy * uz;
		}
		lastX = mouseX;
		lastY = mouseY;
	}

	/** Ends a press: true when it was a left click that never became a drag. */
	public boolean mouseReleased() {
		boolean click = dragButton == InputConstants.MOUSE_BUTTON_LEFT && !moved;
		dragButton = -1;
		return click;
	}

	public void mouseScrolled(double amount) {
		zoom = Mth.clamp(zoom * (float) Math.pow(1.15, amount), minZoom, maxZoom);
	}
}
