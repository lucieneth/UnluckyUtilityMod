package unlucky.utility.client.gui.chests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Vector3fc;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ChestDatabase;
import unlucky.utility.client.util.ChestDatabase.Entry;
import unlucky.utility.client.util.ChestDatabase.Tally;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Every tracked chest at once, where it really stands: the right half of the ChestManager
 * screen. Left-drag orbits, right-drag pans, the wheel zooms, a click picks a chest.
 *
 * <p>Same idea as {@link unlucky.utility.client.gui.clickgui.BlockScene}: an orthographic camera
 * projected in software and painter-sorted by cell, since GUI elements have no depth buffer.
 * Barrels come from their block model, but chests are block entities with no baked quads, so
 * their bottom, lid and latch are rebuilt here from {@code ChestModel}'s boxes and UV layout
 * against the chest textures themselves. That also lets the selected chest open its lid.
 *
 * <p>While a search is showing, the chests that hold it glow in ChestManager's glow colour with
 * their count above them, and the rest fade back.
 */
final class ChestScene {
	private static final float PX = 1.0f / 16.0f;
	/** How far the selected chest's lid swings open, as a fraction of a right angle. */
	private static final float OPEN = 0.55f;
	private static final int FADED_ALPHA = 55;
	private static final String HINT = "Drag to orbit · right-drag to pan · scroll to zoom";
	private static final String SHORT_HINT = "Orbit · pan · zoom";

	private float yaw = 35.0f;
	private float pitch = 40.0f;
	private double focusX, focusY, focusZ;
	private double goalX, goalY, goalZ;
	private float zoom = 1.0f;
	private float goalZoom = 1.0f;
	private float baseScale = 10.0f;
	private boolean framed;
	private int boundsRevision = -1;
	/** How many entries are drawn while they come in after {@link #reveal()}; all of them otherwise. */
	private int shown = Integer.MAX_VALUE;
	private long lastFrame;

	private int dragButton = -1;
	private double lastX, lastY, pressX, pressY;
	private int x, y, width, height;

	// per-frame camera
	private double rx, rz, ux, uy, uz, ex, ey, ez;
	private float scale, originX, originY;

	private final List<Prim> prims = new ArrayList<>();
	private final List<Prim> overlay = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private final List<Shape> shapes = new ArrayList<>();
	/** What {@link #prims} and the rest were last built for. */
	private Frame built;
	private int baseOverlay;
	private final Map<Long, Float> lids = new HashMap<>();
	private final Map<Identifier, TextureSetup> textures = new HashMap<>();

	private Entry selected;
	private Entry hovered;

	/**
	 * One screen quad. {@code texture} is null for flat colour; {@code cell} sorts cells far to
	 * near and {@code part} sorts the boxes inside one cell.
	 */
	private record Prim(float[] xy, float[] uv, Identifier texture, int color, double cell, double part) {
	}

	private record Label(String text, float x, float y, int color) {
	}

	/** An entry's projected outline, for picking and outlining. */
	private record Shape(Entry entry, List<float[]> hull, double depth) {
	}

	Entry selected() {
		return selected;
	}

	Entry hovered() {
		return hovered;
	}

	void select(Entry entry, boolean fly) {
		selected = entry;
		if (entry != null && fly) {
			Vec3 centre = centre(entry);
			goalX = centre.x;
			goalY = centre.y;
			goalZ = centre.z;
			goalZoom = Math.max(goalZoom, 30.0f / baseScale);
		}
	}

	/** Back to the whole base in view. */
	void frameAll() {
		framed = false;
		boundsRevision = -1;
	}

	/** Brings the chests in a batch at a time over the next frames, rather than all in the first. */
	void reveal() {
		shown = 0;
	}

	/** The empty scene with loading dots, while the panel works out what goes in it. */
	void loading(GuiGraphicsExtractor g, int x, int y, int width, int height) {
		hovered = null;
		shapes.clear();
		built = null;
		Render2D.rect(g, x, y, width, height, Theme.borderDark);
		Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, ColorUtil.withAlpha(Theme.surface, 200));
		// centred on the full width, so the dots ticking over don't shift the word
		Render2D.textNoShadow(g, ChestPanel.loadingText(), x + (width - Render2D.width("Loading...")) / 2,
				y + height / 2 - 4, Theme.textDim);
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * @param live whether these are the dimension the player stands in: only then are facings and
	 *             open lids read from the world and the player drawn among them
	 */
	void render(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY,
			List<Entry> entries, Map<Entry, Tally> matches, int glowColor, boolean live) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		Render2D.rect(g, x, y, width, height, Theme.borderDark);
		Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, ColorUtil.withAlpha(Theme.surface, 200));
		if (entries.isEmpty()) {
			hovered = null;
			shapes.clear();
			built = null;
			String empty = "No chests tracked here yet";
			Render2D.textNoShadow(g, empty, x + (width - Render2D.width(empty)) / 2, y + height / 2 - 4,
					Theme.textDim);
			return;
		}
		fit(entries, width - 4, height - 14);
		animate();
		setUpCamera(x + 2, y + 2, width - 4, height - 14);

		Level level = live ? Minecraft.getInstance().level : null;
		int count = Math.min(entries.size(), shown);
		boolean revealing = count < entries.size();
		// a twelfth more each frame: a big base fills in over a fifth of a second instead of one long frame
		shown = revealing ? shown + Math.max(8, entries.size() / 12) : Integer.MAX_VALUE;
		// the world only moves once a tick, so a still camera draws last frame's faces again
		Frame frame = new Frame(focusX, focusY, focusZ, yaw, pitch, scale, originX, originY, x, y, width, height,
				entries, matches, glowColor, level != null ? level.getGameTime() : -1, count,
				ChestDatabase.revision(), Map.copyOf(lids));
		if (!frame.equals(built)) {
			built = frame;
			build(entries.subList(0, count), matches, glowColor, level);
		}
		overlay.subList(baseOverlay, overlay.size()).clear();

		hovered = dragButton < 0 && Render2D.hovered(mouseX, mouseY, x, y, width, height) ? pick(mouseX, mouseY) : null;
		for (Shape shape : shapes) {
			if (shape.entry().equals(selected)) {
				outline(shape.hull(), 0xFFFFFFFF, 1.5f);
			} else if (shape.entry().equals(hovered)) {
				outline(shape.hull(), 0x90FFFFFF, 1.0f);
			}
		}

		g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
		submit(g, prims);
		submit(g, overlay);
		for (Label label : labels) {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(label.x(), label.y());
			pose.scale(0.75f, 0.75f);
			Render2D.text(g, label.text(), -Render2D.width(label.text()) / 2, -Render2D.FONT_HEIGHT, label.color());
			pose.popMatrix();
		}
		g.disableScissor();
		// the hint stays inside the scene: shortened when it's narrow, gone when even that won't fit
		boolean hover = dragButton >= 0 || Render2D.hovered(mouseX, mouseY, x, y, width, height);
		String hint = revealing ? ChestPanel.loadingText() : Render2D.width(HINT) <= width - 8 ? HINT : SHORT_HINT;
		if (Render2D.width(hint) <= width - 8 && height >= 30) {
			Render2D.textNoShadow(g, hint, x + (width - Render2D.width(hint)) / 2, y + height - 11,
					hover ? Theme.text : Theme.textDim);
		}
	}

	/** Everything that decides what the scene's faces look like; while it holds, they're reused. */
	private record Frame(double focusX, double focusY, double focusZ, float yaw, float pitch, float scale,
			float originX, float originY, int x, int y, int width, int height, List<Entry> entries,
			Map<Entry, Tally> matches, int glowColor, long tick, int count, int revision, Map<Long, Float> lids) {
	}

	/** Projects and sorts every chest's faces, labels and outline shapes for the current camera. */
	private void build(List<Entry> entries, Map<Entry, Tally> matches, int glowColor, Level level) {
		prims.clear();
		overlay.clear();
		labels.clear();
		shapes.clear();
		boolean searching = matches != null;
		for (Entry entry : entries) {
			Vec3 centre = centre(entry);
			float sx = screenX(centre.x, centre.z), sy = screenY(centre.x, centre.y, centre.z);
			float margin = scale * 2.0f;
			if (sx < x - margin || sx > x + width + margin || sy < y - margin || sy > y + height + margin) {
				continue;
			}
			Tally tally = searching ? matches.get(entry) : null;
			int alpha = searching && tally == null ? FADED_ALPHA : 255;
			float lid = lid(entry);
			shadow(entry);
			if (isChest(entry.block())) {
				chest(entry, level, alpha, lid);
			} else {
				block(entry, level, alpha, lid > 0.01f);
			}
			List<float[]> hull = hull(corners(entry));
			shapes.add(new Shape(entry, hull, depth(centre.x, centre.y, centre.z)));
			if (tally != null) {
				glow(hull, glowColor);
				labels.add(new Label(label(tally), sx, screenY(centre.x, centre.y + 0.75, centre.z),
						glowColor | 0xFF000000));
			}
		}
		player(level);
		prims.sort((a, b) -> a.cell() != b.cell() ? Double.compare(a.cell(), b.cell()) : Double.compare(a.part(), b.part()));
		// what comes after this is the hover and selection outlines, redrawn every frame
		baseOverlay = overlay.size();
	}

	private static String label(Tally tally) {
		var item = ChestDatabase.item(tally.top());
		String name = item == null ? tally.top() : item.getDefaultInstance().getHoverName().getString();
		return tally.total() + " " + name;
	}

	/** Sizes the camera to the base's spread, and centres on it the first time. */
	private void fit(List<Entry> entries, int vw, int vh) {
		int revision = ChestDatabase.revision();
		if (framed && revision == boundsRevision) {
			return;
		}
		boundsRevision = revision;
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (Entry entry : entries) {
			for (BlockPos pos : entry.other() == null ? List.of(entry.pos()) : List.of(entry.pos(), entry.other())) {
				minX = Math.min(minX, pos.getX());
				minY = Math.min(minY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxX = Math.max(maxX, pos.getX() + 1);
				maxY = Math.max(maxY, pos.getY() + 1);
				maxZ = Math.max(maxZ, pos.getZ() + 1);
			}
		}
		double radius = Math.max(3.0, Math.sqrt(Mth.square(maxX - minX) + Mth.square(maxY - minY)
				+ Mth.square(maxZ - minZ)) / 2.0);
		baseScale = (float) (Math.min(vw, vh) / (2.0 * radius + 1.5));
		if (!framed) {
			framed = true;
			goalX = focusX = (minX + maxX) / 2.0;
			goalY = focusY = (minY + maxY) / 2.0;
			goalZ = focusZ = (minZ + maxZ) / 2.0;
			goalZoom = zoom = 1.0f;
			if (selected != null) {
				select(selected, true);
			}
		}
	}

	private void animate() {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 1.0f : Math.min(0.25f, (now - lastFrame) / 1.0e9f);
		lastFrame = now;
		float k = 1.0f - (float) Math.exp(-dt * 10.0f);
		focusX += (goalX - focusX) * k;
		focusY += (goalY - focusY) * k;
		focusZ += (goalZ - focusZ) * k;
		zoom += (goalZoom - zoom) * k;
		for (Map.Entry<Long, Float> lid : lids.entrySet()) {
			boolean open = selected != null && selected.pos().asLong() == lid.getKey();
			lid.setValue(lid.getValue() + ((open ? OPEN : 0.0f) - lid.getValue()) * k);
		}
		lids.values().removeIf(v -> v < 0.002f);
		if (selected != null) {
			lids.putIfAbsent(selected.pos().asLong(), 0.003f);
		}
	}

	private float lid(Entry entry) {
		return lids.getOrDefault(entry.pos().asLong(), 0.0f);
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
		scale = baseScale * zoom;
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

	private double cellDepth(BlockPos pos) {
		return depth(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	private static Vec3 centre(Entry entry) {
		Vec3 centre = Vec3.atCenterOf(entry.pos());
		return entry.other() == null ? centre : centre.add(Vec3.atCenterOf(entry.other())).scale(0.5);
	}

	// ------------------------------------------------------------------ chests

	private static boolean isChest(String block) {
		return block.endsWith("chest") && !block.endsWith("ender_chest");
	}

	/** The texture family for a chest block id: normal, trapped, or a copper stage. */
	private static String material(String block) {
		if (block.contains("copper")) {
			if (block.contains("oxidized")) {
				return "copper_oxidized";
			}
			if (block.contains("weathered")) {
				return "copper_weathered";
			}
			return block.contains("exposed") ? "copper_exposed" : "copper";
		}
		return block.contains("trapped") ? "trapped" : "normal";
	}

	/**
	 * The way a chest faces: the live block when its chunk is loaded, else what was recorded,
	 * else (older records) square to a double chest's halves, else south.
	 */
	private static Direction facing(Entry entry, Level level) {
		BlockState live = live(entry.pos(), level);
		if (live != null && live.hasProperty(ChestBlock.FACING)) {
			return live.getValue(ChestBlock.FACING);
		}
		if (live != null && live.hasProperty(BarrelBlock.FACING)) {
			return live.getValue(BarrelBlock.FACING);
		}
		if (entry.facing() != null) {
			Direction recorded = Direction.byName(entry.facing());
			if (recorded != null) {
				return recorded;
			}
		}
		if (entry.other() != null) {
			int dx = entry.other().getX() - entry.pos().getX();
			return dx != 0 ? Direction.SOUTH : Direction.EAST;
		}
		return isChest(entry.block()) ? Direction.SOUTH : Direction.UP;
	}

	private static BlockState live(BlockPos pos, Level level) {
		if (level == null || !level.isLoaded(pos)) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		return ChestDatabase.trackable(state) ? state : null;
	}

	private void chest(Entry entry, Level level, int alpha, float lid) {
		Direction facing = facing(entry, level);
		String material = material(entry.block());
		if (entry.other() == null) {
			chestHalf(entry.pos(), facing, ChestType.SINGLE, texture(material), alpha, lid);
			return;
		}
		// A left half's partner is clockwise of its facing (ChestBlock.getConnectedDirection).
		BlockState live = live(entry.pos(), level);
		ChestType type = live != null && live.hasProperty(ChestBlock.TYPE) && live.getValue(ChestBlock.TYPE) != ChestType.SINGLE
				? live.getValue(ChestBlock.TYPE)
				: entry.pos().relative(facing.getClockWise()).equals(entry.other()) ? ChestType.LEFT : ChestType.RIGHT;
		ChestType otherType = type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;
		chestHalf(entry.pos(), facing, type, texture(material + (type == ChestType.LEFT ? "_left" : "_right")), alpha, lid);
		chestHalf(entry.other(), facing, otherType,
				texture(material + (otherType == ChestType.LEFT ? "_left" : "_right")), alpha, lid);
	}

	private static Identifier texture(String name) {
		return Identifier.withDefaultNamespace("textures/entity/chest/" + name + ".png");
	}

	/** ChestModel's three boxes for one half, in pixels with the front facing south. */
	private void chestHalf(BlockPos pos, Direction facing, ChestType type, Identifier texture, int alpha, float lid) {
		// the side a double half joins its partner on has no face
		Direction hidden = switch (type) {
			case LEFT -> Direction.WEST;
			case RIGHT -> Direction.EAST;
			default -> null;
		};
		float minX = type == ChestType.LEFT ? 0 : 1;
		float width = type == ChestType.SINGLE ? 14 : 15;
		float lockX = switch (type) {
			case LEFT -> 0;
			case RIGHT -> 15;
			default -> 7;
		};
		float lockW = type == ChestType.SINGLE ? 2 : 1;
		float angle = -lid * Mth.HALF_PI;
		boolean open = lid > 0.01f;
		double cell = cellDepth(pos);
		// the bottom's top and the lid's underside only show while it is open
		box(pos, facing, texture, alpha, cell, 0, 19, minX, 0, 1, width, 10, 14, hidden, open ? null : Direction.UP, 0);
		box(pos, facing, texture, alpha, cell, 0, 0, minX, 9, 1, width, 5, 14, hidden, open ? null : Direction.DOWN, angle);
		box(pos, facing, texture, alpha, cell, 0, 0, lockX, 7, 15, lockW, 4, 1, hidden, null, angle);
	}

	/**
	 * One {@code ModelPart.Cube}, with its polygons, vertex order and UVs exactly as vanilla
	 * builds them, so the chest textures land where they do in the world. {@code hinge} turns the
	 * box about the lid's pivot (y 9, z 1), as the lid's {@code xRot} does.
	 */
	private void box(BlockPos pos, Direction facing, Identifier texture, int alpha, double cell, int texU, int texV,
			float minX, float minY, float minZ, float w, float h, float d, Direction hidden, Direction skip,
			float hinge) {
		float maxX = minX + w, maxY = minY + h, maxZ = minZ + d;
		float[][] corners = {
				{minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ}, // t0..t3
				{minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}}; // l0..l3
		float u0 = texU, u1 = u0 + d, u2 = u1 + w, u22 = u2 + w, u3 = u2 + d, u4 = u3 + w;
		float v0 = texV, v1 = v0 + d, v2 = v1 + h;
		int[][] faces = {{5, 4, 0, 1}, {2, 3, 7, 6}, {0, 4, 7, 3}, {1, 0, 3, 2}, {5, 1, 2, 6}, {4, 5, 6, 7}};
		Direction[] sides = {Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH};
		float[][] rects = {{u1, v0, u2, v1}, {u2, v1, u22, v0}, {u0, v1, u1, v2}, {u1, v1, u2, v2}, {u2, v1, u3, v2},
				{u3, v1, u4, v2}};
		double cos = Math.cos(hinge), sin = Math.sin(hinge);
		double turn = Math.toRadians(-facing.toYRot());
		double tc = Math.cos(turn), ts = Math.sin(turn);
		// the box's centre, for sorting it against the cell's other boxes
		double[] middle = place(pos, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, cos, sin, tc, ts);
		double part = depth(middle[0], middle[1], middle[2]);
		for (int f = 0; f < 6; f++) {
			Direction side = sides[f];
			if (side == hidden || side == skip) {
				continue;
			}
			// the face's normal through the hinge, then the facing turn
			double ny = side.getStepY() * cos - side.getStepZ() * sin;
			double nz = side.getStepY() * sin + side.getStepZ() * cos;
			double nx = side.getStepX();
			double wx = nx * tc + nz * ts, wz = -nx * ts + nz * tc;
			if (wx * ex + ny * ey + wz * ez <= 1.0e-4) {
				continue;
			}
			float[] xy = new float[8];
			float[] uv = new float[8];
			float[] rect = rects[f];
			float[][] corner = {{rect[2], rect[1]}, {rect[0], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}};
			for (int i = 0; i < 4; i++) {
				float[] c = corners[faces[f][i]];
				double[] p = place(pos, c[0], c[1], c[2], cos, sin, tc, ts);
				xy[i * 2] = screenX(p[0], p[2]);
				xy[i * 2 + 1] = screenY(p[0], p[1], p[2]);
				uv[i * 2] = corner[i][0] / 64.0f;
				uv[i * 2 + 1] = corner[i][1] / 64.0f;
			}
			prims.add(new Prim(xy, uv, texture, shaded(0xFFFFFFFF, shade(wx, ny, wz), alpha), cell, part));
		}
	}

	/** A model point in pixels → world: through the lid hinge, then turned to face its way. */
	private static double[] place(BlockPos pos, double px, double py, double pz, double cos, double sin, double tc,
			double ts) {
		double ly = py * PX - 9 * PX, lz = pz * PX - PX;
		double hy = ly * cos - lz * sin + 9 * PX;
		double hz = ly * sin + lz * cos + PX;
		double cx = px * PX - 0.5, cz = hz - 0.5;
		return new double[] {pos.getX() + 0.5 + cx * tc + cz * ts, pos.getY() + hy, pos.getZ() + 0.5 - cx * ts + cz * tc};
	}

	/** The block-model look's face shading, for any normal. */
	private static float shade(double nx, double ny, double nz) {
		return (float) (Math.abs(nx) * 0.6 + Math.abs(nz) * 0.8 + (ny > 0 ? ny : -ny * 0.5));
	}

	private static int shaded(int tint, float shade, int alpha) {
		shade = Math.min(1.0f, shade);
		return ColorUtil.argb(alpha, (int) (((tint >> 16) & 0xFF) * shade), (int) (((tint >> 8) & 0xFF) * shade),
				(int) ((tint & 0xFF) * shade));
	}

	// ------------------------------------------------------------------ barrels

	private void block(Entry entry, Level level, int alpha, boolean open) {
		BlockState state = live(entry.pos(), level);
		if (state == null) {
			Identifier id = Identifier.tryParse(entry.block());
			Block block = id == null ? Blocks.BARREL : BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.BARREL);
			state = block.defaultBlockState();
			if (state.hasProperty(BarrelBlock.FACING)) {
				state = state.setValue(BarrelBlock.FACING, facing(entry, level));
			}
		}
		if (state.hasProperty(BarrelBlock.OPEN)) {
			state = state.setValue(BarrelBlock.OPEN, open);
		}
		RandomSource random = RandomSource.create(42L);
		List<BlockStateModelPart> parts = new ArrayList<>();
		Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).collectParts(random, parts);
		double cell = cellDepth(entry.pos());
		Identifier atlas = TextureAtlas.LOCATION_BLOCKS;
		for (BlockStateModelPart part : parts) {
			for (Direction side : Direction.values()) {
				quads(part.getQuads(side), entry.pos(), atlas, alpha, cell);
			}
			quads(part.getQuads(null), entry.pos(), atlas, alpha, cell);
		}
	}

	private void quads(List<BakedQuad> quads, BlockPos pos, Identifier atlas, int alpha, double cell) {
		for (BakedQuad quad : quads) {
			Direction facing = quad.direction();
			if (facing.getStepX() * ex + facing.getStepY() * ey + facing.getStepZ() * ez <= 1.0e-4) {
				continue;
			}
			float[] xy = new float[8];
			float[] uv = new float[8];
			for (int i = 0; i < 4; i++) {
				Vector3fc p = quad.position(i);
				double wx = pos.getX() + p.x(), wy = pos.getY() + p.y(), wz = pos.getZ() + p.z();
				xy[i * 2] = screenX(wx, wz);
				xy[i * 2 + 1] = screenY(wx, wy, wz);
				long packed = quad.packedUV(i);
				uv[i * 2] = UVPair.unpackU(packed);
				uv[i * 2 + 1] = UVPair.unpackV(packed);
			}
			float shade = shade(facing.getStepX(), facing.getStepY(), facing.getStepZ());
			prims.add(new Prim(xy, uv, atlas, shaded(0xFFFFFFFF, shade, alpha), cell, 0));
		}
	}

	// ------------------------------------------------------------------ the rest of the picture

	/** A soft dark footprint under each container, so stacks and rows read as standing on something. */
	private void shadow(Entry entry) {
		double x0 = Math.min(entry.pos().getX(), entry.other() == null ? entry.pos().getX() : entry.other().getX());
		double z0 = Math.min(entry.pos().getZ(), entry.other() == null ? entry.pos().getZ() : entry.other().getZ());
		double x1 = Math.max(entry.pos().getX(), entry.other() == null ? entry.pos().getX() : entry.other().getX()) + 1;
		double z1 = Math.max(entry.pos().getZ(), entry.other() == null ? entry.pos().getZ() : entry.other().getZ()) + 1;
		double fy = entry.pos().getY();
		if (ey <= 0) {
			return;
		}
		float[] xy = {screenX(x0 - 0.08, z0 - 0.08), screenY(x0 - 0.08, fy, z0 - 0.08),
				screenX(x1 + 0.08, z0 - 0.08), screenY(x1 + 0.08, fy, z0 - 0.08),
				screenX(x1 + 0.08, z1 + 0.08), screenY(x1 + 0.08, fy, z1 + 0.08),
				screenX(x0 - 0.08, z1 + 0.08), screenY(x0 - 0.08, fy, z1 + 0.08)};
		prims.add(new Prim(xy, null, null, 0x38000000, -Double.MAX_VALUE, 0));
	}

	/** Where you are standing, when it is anywhere near the chests. */
	private void player(Level level) {
		var player = Minecraft.getInstance().player;
		if (player == null || level == null) {
			return;
		}
		Vec3 feet = player.position();
		float sx = screenX(feet.x, feet.z), sy = screenY(feet.x, feet.y, feet.z);
		if (sx < x || sx > x + width || sy < y || sy > y + height) {
			return;
		}
		double r = 0.3;
		float[] xy = {screenX(feet.x, feet.z - r), screenY(feet.x, feet.y + 0.02, feet.z - r),
				screenX(feet.x + r, feet.z), screenY(feet.x + r, feet.y + 0.02, feet.z),
				screenX(feet.x, feet.z + r), screenY(feet.x, feet.y + 0.02, feet.z + r),
				screenX(feet.x - r, feet.z), screenY(feet.x - r, feet.y + 0.02, feet.z)};
		overlay.add(new Prim(xy, null, null, ColorUtil.withAlpha(Theme.accent1, 220), 0, 0));
		labels.add(new Label("You", sx, sy - 2, Theme.accent1 | 0xFF000000));
	}

	/** The eight corners of what an entry looks like from outside: the chest's body, or a full barrel. */
	private float[][] corners(Entry entry) {
		boolean chest = isChest(entry.block());
		double inset = chest ? PX : 0.0, top = chest ? 14 * PX : 1.0;
		BlockPos a = entry.pos(), b = entry.other() == null ? entry.pos() : entry.other();
		double x0 = Math.min(a.getX(), b.getX()) + inset, x1 = Math.max(a.getX(), b.getX()) + 1 - inset;
		double z0 = Math.min(a.getZ(), b.getZ()) + inset, z1 = Math.max(a.getZ(), b.getZ()) + 1 - inset;
		double y0 = a.getY(), y1 = a.getY() + top;
		float[][] corners = new float[8][];
		for (int i = 0; i < 8; i++) {
			double cx = (i & 1) == 0 ? x0 : x1, cy = (i & 2) == 0 ? y0 : y1, cz = (i & 4) == 0 ? z0 : z1;
			corners[i] = new float[] {screenX(cx, cz), screenY(cx, cy, cz)};
		}
		return corners;
	}

	/** Shader's glow look: soft rings around the outline, over everything. */
	private void glow(List<float[]> hull, int color) {
		int argb = color | 0xFF000000;
		for (int i = 1; i + 1 < hull.size(); i++) {
			float[] a = hull.getFirst(), b = hull.get(i), c = hull.get(i + 1);
			overlay.add(new Prim(new float[] {a[0], a[1], b[0], b[1], c[0], c[1], c[0], c[1]}, null, null,
					ColorUtil.multiplyAlpha(argb, 0.18f), 0, 0));
		}
		for (int ring = 3; ring >= 1; ring--) {
			outline(hull, ColorUtil.multiplyAlpha(argb, 0.35f / ring + 0.15f), 1.2f * ring);
		}
	}

	private void outline(List<float[]> hull, int color, float lineWidth) {
		for (int i = 0; i < hull.size(); i++) {
			float[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
			float[] xy = strip(a[0], a[1], b[0], b[1], lineWidth);
			if (xy != null) {
				overlay.add(new Prim(xy, null, null, color, 0, 0));
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

	private static boolean inside(List<float[]> hull, double px, double py) {
		if (hull.size() < 3) {
			return false;
		}
		boolean positive = false, negative = false;
		for (int i = 0; i < hull.size(); i++) {
			float[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
			double cross = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
			positive |= cross > 0;
			negative |= cross < 0;
		}
		return !(positive && negative);
	}

	/** The nearest container under the mouse. */
	private Entry pick(double mouseX, double mouseY) {
		Shape best = null;
		for (Shape shape : shapes) {
			if (inside(shape.hull(), mouseX, mouseY) && (best == null || shape.depth() > best.depth())) {
				best = shape;
			}
		}
		return best == null ? null : best.entry();
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
			if (i == list.size() || !java.util.Objects.equals(list.get(i).texture(), list.get(start).texture())) {
				Identifier texture = list.get(start).texture();
				g.guiRenderState.addGuiElement(new Batch(
						texture != null ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI,
						texture != null ? setup(texture) : TextureSetup.noTexture(),
						pose, List.copyOf(list.subList(start, i)), scissor, bounds));
				start = i;
			}
		}
	}

	private TextureSetup setup(Identifier texture) {
		return textures.computeIfAbsent(texture, id -> {
			AbstractTexture loaded = Minecraft.getInstance().getTextureManager().getTexture(id);
			return TextureSetup.singleTexture(loaded.getTextureView(), loaded.getSampler());
		});
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
		return true;
	}

	void mouseDragged(double mouseX, double mouseY) {
		if (dragButton == InputConstants.MOUSE_BUTTON_LEFT) {
			yaw = Mth.wrapDegrees(yaw + (float) (mouseX - lastX) * 1.2f);
			pitch = Mth.clamp(pitch + (float) (mouseY - lastY) * 0.6f, 8.0f, 89.0f);
		} else if (dragButton == InputConstants.MOUSE_BUTTON_RIGHT && scale > 0) {
			// slide the scene with the mouse: back along the screen's right and up vectors
			double dx = (mouseX - lastX) / scale, dy = (mouseY - lastY) / scale;
			goalX = focusX -= dx * rx - dy * ux;
			goalY = focusY -= -dy * uy;
			goalZ = focusZ -= dx * rz - dy * uz;
		}
		lastX = mouseX;
		lastY = mouseY;
	}

	/** @return the container clicked, when this release ends a click rather than a drag */
	Entry mouseReleased(double mouseX, double mouseY) {
		boolean click = dragButton == InputConstants.MOUSE_BUTTON_LEFT
				&& Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) < 4;
		dragButton = -1;
		return click ? pick(mouseX, mouseY) : null;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}
		float max = 64.0f / Math.max(0.01f, baseScale);
		goalZoom = Mth.clamp(goalZoom * (float) Math.pow(1.2, amount), 0.5f, Math.max(1.0f, max));
		return true;
	}
}
