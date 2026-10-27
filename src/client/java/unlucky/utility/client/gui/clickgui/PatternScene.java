package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix3x2f;
import org.joml.Vector3fc;
import unlucky.utility.client.settings.PatternSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * A {@link PatternSetting} in 3D: obsidian where the pattern places, a little player in the middle
 * and a 5×5 grid on the layer being edited. Click a tile to place there, click a block to take it
 * away; left-drag orbits, right-drag pans, the wheel zooms.
 *
 * <p>The ChestManager scene's renderer, cut down to cells: the same software orthographic camera,
 * painter-sorted by cell since GUI elements have no depth buffer, and the obsidian comes from its
 * real block model so it looks like what will be placed. Blocks off the edited layer fade back so
 * the layer reads through them, and a face two pattern blocks share is never drawn.
 */
final class PatternScene {
	private static final float PX = 1.0f / 16.0f;
	private static final int FADED_ALPHA = 80;
	private static final String HINT = "Click to place or remove · drag to orbit";
	private static final String SHORT_HINT = "Click · drag · scroll";

	private float yaw = 35.0f;
	private float pitch = 32.0f;
	private double focusX = 0.5, focusY = 1.0, focusZ = 0.5;
	private float zoom = 1.0f;
	private int layer;

	private int dragButton = -1;
	private double lastX, lastY, pressX, pressY;
	private boolean moved;
	private int x, y, width, height;

	// per-frame camera
	private double rx, rz, ux, uy, uz, ex, ey, ez;
	private float scale, originX, originY;

	private final List<Prim> prims = new ArrayList<>();
	private final List<Prim> overlay = new ArrayList<>();
	private final List<Target> targets = new ArrayList<>();
	private List<BlockStateModelPart> obsidian;
	private TextureSetup atlas;
	private Target hovered;

	private record Prim(float[] xy, float[] uv, boolean textured, int color, double cell, double part) {
	}

	/** Something a click can land on: a placed block, or an empty tile on the edited layer. */
	private record Target(int cx, int cy, int cz, boolean placed, List<float[]> hull, double depth) {
	}

	int layer() {
		return layer;
	}

	void layer(int layer) {
		this.layer = Mth.clamp(layer, PatternSetting.MIN_Y, PatternSetting.MAX_Y);
	}

	void reset() {
		yaw = 35.0f;
		pitch = 32.0f;
		focusX = 0.5;
		focusY = 1.0;
		focusZ = 0.5;
		zoom = 1.0f;
		layer = 0;
		dragButton = -1;
	}

	// ------------------------------------------------------------------ drawing

	void render(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY,
			PatternSetting pattern) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		Render2D.rect(g, x, y, width, height, Theme.borderDark);
		Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, ColorUtil.withAlpha(Theme.surface, 200));
		setUpCamera(x + 2, y + 2, width - 4, height - 14);

		prims.clear();
		overlay.clear();
		targets.clear();
		boolean[][][] filled = new boolean[5][5][5];
		for (int[] cell : pattern.cells()) {
			if (PatternSetting.allowed(cell[0], cell[1], cell[2])) {
				filled[cell[0] + 2][cell[1] + 1][cell[2] + 2] = true;
			}
		}
		for (int cx = -2; cx <= 2; cx++) {
			for (int cy = PatternSetting.MIN_Y; cy <= PatternSetting.MAX_Y; cy++) {
				for (int cz = -2; cz <= 2; cz++) {
					if (filled[cx + 2][cy + 1][cz + 2]) {
						block(cx, cy, cz, filled, cy == layer ? 255 : FADED_ALPHA);
						targets.add(new Target(cx, cy, cz, true, hull(corners(cx, cy, cz, 0, 1)),
								depth(cx + 0.5, cy + 0.5, cz + 0.5)));
					} else if (cy == layer) {
						tile(cx, cz, PatternSetting.allowed(cx, cy, cz));
					}
				}
			}
		}
		player();
		prims.sort((a, b) -> a.cell() != b.cell() ? Double.compare(a.cell(), b.cell()) : Double.compare(a.part(), b.part()));

		hovered = Render2D.hovered(mouseX, mouseY, x, y, width, height) && (dragButton < 0 || !moved)
				? pick(mouseX, mouseY) : null;
		if (hovered != null) {
			List<float[]> cube = hull(corners(hovered.cx(), hovered.cy(), hovered.cz(), 0, 1));
			if (hovered.placed()) {
				outline(cube, 0xFFFF6060, 1.5f);
			} else {
				fill(cube, ColorUtil.withAlpha(Theme.accent1, 70));
				outline(cube, ColorUtil.withAlpha(Theme.accent1, 230), 1.2f);
			}
		}

		g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
		submit(g, prims);
		submit(g, overlay);
		g.disableScissor();
		boolean hover = dragButton >= 0 || Render2D.hovered(mouseX, mouseY, x, y, width, height);
		String hint = Render2D.width(HINT) <= width - 8 ? HINT : SHORT_HINT;
		if (Render2D.width(hint) <= width - 8) {
			Render2D.textNoShadow(g, hint, x + (width - Render2D.width(hint)) / 2, y + height - 11,
					hover ? Theme.text : Theme.textDim);
		}
	}

	private void setUpCamera(int vx, int vy, int vw, int vh) {
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
		// the whole 5×5×5 grid, about 4.3 blocks out from the middle, fits at zoom 1
		scale = Math.min(vw, vh) / 9.0f * zoom;
		originX = vx + vw / 2.0f;
		originY = vy + vh / 2.0f;
	}

	private float screenX(double px, double pz) {
		return originX + (float) ((px - focusX) * rx + (pz - focusZ) * rz) * scale;
	}

	private float screenY(double px, double py, double pz) {
		return originY - (float) ((px - focusX) * ux + (py - focusY) * uy + (pz - focusZ) * uz) * scale;
	}

	private double depth(double px, double py, double pz) {
		return (px - focusX) * ex + (py - focusY) * ey + (pz - focusZ) * ez;
	}

	private double cellDepth(int cx, int cy, int cz) {
		return depth(cx + 0.5, cy + 0.5, cz + 0.5);
	}

	/** One obsidian block from its baked model, without the faces it shares with another. */
	private void block(int cx, int cy, int cz, boolean[][][] filled, int alpha) {
		if (obsidian == null) {
			obsidian = new ArrayList<>();
			Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(Blocks.OBSIDIAN.defaultBlockState())
					.collectParts(RandomSource.create(42L), obsidian);
		}
		double cell = cellDepth(cx, cy, cz);
		for (BlockStateModelPart part : obsidian) {
			for (Direction side : Direction.values()) {
				int nx = cx + side.getStepX(), ny = cy + side.getStepY(), nz = cz + side.getStepZ();
				boolean shared = Math.abs(nx) <= 2 && Math.abs(nz) <= 2 && ny >= PatternSetting.MIN_Y
						&& ny <= PatternSetting.MAX_Y && filled[nx + 2][ny + 1][nz + 2];
				// a faded block's shared faces still show, or the layer behind would look hollow
				if (!shared || alpha < 255) {
					quads(part.getQuads(side), cx, cy, cz, alpha, cell);
				}
			}
			quads(part.getQuads(null), cx, cy, cz, alpha, cell);
		}
	}

	private void quads(List<BakedQuad> quads, int cx, int cy, int cz, int alpha, double cell) {
		for (BakedQuad quad : quads) {
			Direction facing = quad.direction();
			if (facing.getStepX() * ex + facing.getStepY() * ey + facing.getStepZ() * ez <= 1.0e-4) {
				continue;
			}
			float[] xy = new float[8];
			float[] uv = new float[8];
			for (int i = 0; i < 4; i++) {
				Vector3fc p = quad.position(i);
				double wx = cx + p.x(), wy = cy + p.y(), wz = cz + p.z();
				xy[i * 2] = screenX(wx, wz);
				xy[i * 2 + 1] = screenY(wx, wy, wz);
				long packed = quad.packedUV(i);
				uv[i * 2] = UVPair.unpackU(packed);
				uv[i * 2 + 1] = UVPair.unpackV(packed);
			}
			float shade = shade(facing.getStepX(), facing.getStepY(), facing.getStepZ());
			prims.add(new Prim(xy, uv, true, shaded(0xFFFFFFFF, shade, alpha), cell, 0));
		}
	}

	/**
	 * An empty cell on the edited layer: a faint square on its floor, sorted as if it were the
	 * cell's bottom so blocks above cover it and blocks below don't.
	 */
	private void tile(int cx, int cz, boolean allowed) {
		double fy = layer;
		double inset = 0.06;
		float[] xy = {screenX(cx + inset, cz + inset), screenY(cx + inset, fy, cz + inset),
				screenX(cx + 1 - inset, cz + inset), screenY(cx + 1 - inset, fy, cz + inset),
				screenX(cx + 1 - inset, cz + 1 - inset), screenY(cx + 1 - inset, fy, cz + 1 - inset),
				screenX(cx + inset, cz + 1 - inset), screenY(cx + inset, fy, cz + 1 - inset)};
		double cell = cellDepth(cx, layer, cz) - 0.5 * ey;
		boolean ring = Math.abs(cx) <= 1 && Math.abs(cz) <= 1;
		int color = !allowed ? 0x00000000 : ColorUtil.withAlpha(ring ? Theme.accent1 : Theme.textDim, ring ? 60 : 36);
		if (allowed) {
			prims.add(new Prim(xy, null, false, color, cell, -1));
			List<float[]> square = new ArrayList<>(4);
			for (int i = 0; i < 4; i++) {
				square.add(new float[] {xy[i * 2], xy[i * 2 + 1]});
			}
			for (int i = 0; i < 4; i++) {
				float[] a = square.get(i), b = square.get((i + 1) % 4);
				float[] line = strip(a[0], a[1], b[0], b[1], 1.0f);
				if (line != null) {
					prims.add(new Prim(line, null, false, ColorUtil.withAlpha(ring ? Theme.accent1 : Theme.textDim, 110),
							cell, -0.5));
				}
			}
			targets.add(new Target(cx, layer, cz, false, square, depth(cx + 0.5, fy, cz + 0.5)));
		}
	}

	/** A blocky figure standing in the feet and head cells: legs, body, arms and head. */
	private void player() {
		int pants = 0xFF3B4290, shirt = 0xFF2E9C9A, skin = 0xFFC9946E;
		figureBox(4, 0, 6, 12, 12, 10, pants);
		figureBox(4, 12, 6, 12, 24, 10, shirt);
		figureBox(0, 12, 6, 4, 24, 10, skin);
		figureBox(12, 12, 6, 16, 24, 10, skin);
		figureBox(4, 24, 4, 12, 32, 12, skin);
	}

	/** One box of the figure, its corners in pixels from the feet cell's corner. */
	private void figureBox(float px0, float py0, float pz0, float px1, float py1, float pz1, int color) {
		double x0 = px0 * PX, x1 = px1 * PX, y0 = py0 * PX, y1 = py1 * PX, z0 = pz0 * PX, z1 = pz1 * PX;
		double centreY = (y0 + y1) / 2;
		int cellY = centreY >= 1 ? 1 : 0;
		double cell = cellDepth(0, cellY, 0);
		double part = depth((x0 + x1) / 2, centreY, (z0 + z1) / 2);
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = new double[] {(i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1};
		}
		int[][] faces = {{0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 3, 2}, {4, 5, 7, 6}};
		Direction[] sides = {Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST, Direction.NORTH,
				Direction.SOUTH};
		for (int f = 0; f < 6; f++) {
			Direction side = sides[f];
			if (side.getStepX() * ex + side.getStepY() * ey + side.getStepZ() * ez <= 1.0e-4) {
				continue;
			}
			float[] xy = new float[8];
			for (int i = 0; i < 4; i++) {
				double[] p = c[faces[f][i]];
				xy[i * 2] = screenX(p[0], p[2]);
				xy[i * 2 + 1] = screenY(p[0], p[1], p[2]);
			}
			prims.add(new Prim(xy, null, false,
					shaded(color, shade(side.getStepX(), side.getStepY(), side.getStepZ()), 255), cell, part));
		}
	}

	private static float shade(double nx, double ny, double nz) {
		return (float) (Math.abs(nx) * 0.6 + Math.abs(nz) * 0.8 + (ny > 0 ? ny : -ny * 0.5));
	}

	private static int shaded(int tint, float shade, int alpha) {
		shade = Math.min(1.0f, shade);
		return ColorUtil.argb(alpha, (int) (((tint >> 16) & 0xFF) * shade), (int) (((tint >> 8) & 0xFF) * shade),
				(int) ((tint & 0xFF) * shade));
	}

	private float[][] corners(int cx, int cy, int cz, double y0, double y1) {
		float[][] corners = new float[8][];
		for (int i = 0; i < 8; i++) {
			double px = cx + ((i & 1) == 0 ? 0 : 1), py = cy + ((i & 2) == 0 ? y0 : y1), pz = cz + ((i & 4) == 0 ? 0 : 1);
			corners[i] = new float[] {screenX(px, pz), screenY(px, py, pz)};
		}
		return corners;
	}

	private void fill(List<float[]> hull, int color) {
		for (int i = 1; i + 1 < hull.size(); i++) {
			float[] a = hull.getFirst(), b = hull.get(i), c = hull.get(i + 1);
			overlay.add(new Prim(new float[] {a[0], a[1], b[0], b[1], c[0], c[1], c[0], c[1]}, null, false, color, 0, 0));
		}
	}

	private void outline(List<float[]> hull, int color, float lineWidth) {
		for (int i = 0; i < hull.size(); i++) {
			float[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
			float[] xy = strip(a[0], a[1], b[0], b[1], lineWidth);
			if (xy != null) {
				overlay.add(new Prim(xy, null, false, color, 0, 0));
			}
		}
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
	private static List<float[]> hull(float[][] points) {
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

	private static boolean inside(List<float[]> polygon, double px, double py) {
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

	/**
	 * What is under the mouse. Blocks on the edited layer come first, then its empty tiles, then
	 * the faded blocks off it, each the nearest of its kind: the faded ones are see-through, so a
	 * click lands on the layer you are working on unless nothing on it is there.
	 */
	private Target pick(double mouseX, double mouseY) {
		Target best = null;
		int bestRank = Integer.MAX_VALUE;
		for (Target target : targets) {
			if (!inside(target.hull(), mouseX, mouseY)) {
				continue;
			}
			int rank = target.cy() == layer ? (target.placed() ? 0 : 1) : 2;
			if (rank < bestRank || (rank == bestRank && target.depth() > best.depth())) {
				best = target;
				bestRank = rank;
			}
		}
		return best;
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

	boolean contains(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x, y, width, height);
	}

	boolean mouseClicked(double mouseX, double mouseY, int button) {
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

	boolean dragging() {
		return dragButton >= 0;
	}

	void mouseDragged(double mouseX, double mouseY) {
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

	/** Ends a press; a left click that never became a drag toggles the cell it landed on. */
	void mouseReleased(PatternSetting pattern) {
		boolean click = dragButton == InputConstants.MOUSE_BUTTON_LEFT && !moved;
		dragButton = -1;
		if (click) {
			Target target = pick(pressX, pressY);
			if (target != null) {
				pattern.toggle(target.cx(), target.cy(), target.cz());
			}
		}
	}

	void mouseScrolled(double amount) {
		zoom = Mth.clamp(zoom * (float) Math.pow(1.15, amount), 0.5f, 4.0f);
	}
}
