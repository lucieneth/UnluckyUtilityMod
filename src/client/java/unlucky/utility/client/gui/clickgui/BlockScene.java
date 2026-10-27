package unlucky.utility.client.gui.clickgui;

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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Vector3fc;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.render.BlockOutline;
import unlucky.utility.client.module.modules.render.BreakIndicators;
import unlucky.utility.client.module.modules.render.HoleESP;
import unlucky.utility.client.module.modules.render.LightOverlay;
import unlucky.utility.client.module.modules.render.Shader;
import unlucky.utility.client.module.modules.render.VoidESP;
import unlucky.utility.client.module.modules.render.XRay;
import unlucky.utility.client.module.modules.world.Search;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.HoleUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * The Blocks half of the Preview tab: a little 5×5 diorama with one of everything the block
 * modules draw on — a selected block, one being mined, a crystal hole, a gap in the floor, dark
 * spots, a barrel and an ore — drawn with the real block textures and orbited by dragging.
 *
 * <p><b>The overlays are the modules' own.</b> Each one gets a {@code preview(BlockScene)} that
 * runs its ordinary styling code inside {@link Render3D#drawInto}, which turns the gizmo calls
 * into quads and lines on this scene instead of the world. The scene is also the
 * {@link BlockGetter} those calls see, so shapes and map colours come from the diorama, and the
 * camera it hands them makes their usual back-face and hidden-edge culling work unchanged.
 *
 * <p><b>Depth is painter's order, not a depth buffer</b>, since GUI elements have none. On a
 * unit grid seen orthographically, drawing whole cells far to near is exact, so block faces sort
 * by their cell and each overlay call is pinned just after the cell it marks — which puts an
 * outline over its own block and under anything standing in front of it, the way the depth test
 * would. "Through walls" overlays go after everything, like {@code setAlwaysOnTop}.
 */
public final class BlockScene implements BlockGetter {
	/** The modules this scene shows, in the order the Blocks sub-tab lists them. */
	public static final List<Class<? extends Module>> MODULES = List.of(
			BlockOutline.class, BreakIndicators.class, HoleESP.class, VoidESP.class,
			LightOverlay.class, Search.class, XRay.class, Shader.class);

	public record Hole(BlockPos pos, HoleUtil.Material material) {
	}

	public record Spot(BlockPos pos, boolean dangerous) {
	}

	private static final String HINT = "Drag to orbit";
	private static final int GRASS_TINT = 0xFF79C05A;
	private static final double CENTER_X = 2.5, CENTER_Y = 0.0, CENTER_Z = 2.5;

	private static final BlockPos SELECTED = new BlockPos(3, 0, 1);
	private static final BlockPos BREAKING = new BlockPos(0, 0, 1);
	private static final BlockPos BARREL = new BlockPos(3, 0, 3);
	private static final BlockPos ORE = new BlockPos(4, 0, 0);
	private static final BlockPos VOID = new BlockPos(4, -1, 4);
	private static final List<Hole> HOLES = List.of(new Hole(new BlockPos(1, 0, 3), HoleUtil.Material.OBSIDIAN));
	private static final List<Spot> SPOTS = List.of(new Spot(new BlockPos(0, 0, 0), true),
			new Spot(new BlockPos(4, 0, 2), false));

	private static final Map<BlockPos, BlockState> BLOCKS = build();

	private float yaw = 35.0f;
	private float pitch = 30.0f;
	private boolean dragging;
	private double lastX, lastY;
	private int x, y, width, height;

	// per-frame camera
	private double rx, rz, ux, uy, uz, ex, ey, ez;
	private float scale, originX, originY;
	private BlockPos anchor;
	private boolean xray;
	private XRay xrayModule;
	private final List<Prim> prims = new ArrayList<>();
	private final List<Prim> onTop = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();

	/** One screen quad: four corners, optional atlas UVs, one colour, and where it sorts. */
	private record Prim(float[] xy, float[] uv, int color, double depth) {
	}

	private record Label(String text, float x, float y, int color, float scale) {
	}

	private static Map<BlockPos, BlockState> build() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int bx = 0; bx < 5; bx++) {
			for (int bz = 0; bz < 5; bz++) {
				blocks.put(new BlockPos(bx, -1, bz), Blocks.GRASS_BLOCK.defaultBlockState());
			}
		}
		blocks.remove(VOID);
		BlockPos hole = HOLES.getFirst().pos();
		blocks.put(hole.below(), Blocks.OBSIDIAN.defaultBlockState());
		for (Direction side : Direction.Plane.HORIZONTAL) {
			blocks.put(hole.relative(side), Blocks.OBSIDIAN.defaultBlockState());
		}
		blocks.put(SELECTED, Blocks.STONE.defaultBlockState());
		blocks.put(BREAKING, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BARREL, Blocks.BARREL.defaultBlockState());
		blocks.put(ORE, Blocks.DIAMOND_ORE.defaultBlockState());
		return Map.copyOf(blocks);
	}

	// ------------------------------------------------------------------ what the modules read

	public BlockPos selected() {
		return SELECTED;
	}

	public Direction selectedFace() {
		return Direction.UP;
	}

	public BlockPos breaking() {
		return BREAKING;
	}

	/** Loops 0 → 1 every two and a half seconds. */
	public float breakProgress() {
		return (System.currentTimeMillis() % 2500L) / 2500.0f;
	}

	public List<Hole> holes() {
		return HOLES;
	}

	public BlockPos voidHole() {
		return VOID;
	}

	public List<Spot> darkSpots() {
		return SPOTS;
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return BLOCKS.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return Fluids.EMPTY.defaultFluidState();
	}

	@Override
	public int getMinY() {
		return -1;
	}

	@Override
	public int getHeight() {
		return 4;
	}

	// ------------------------------------------------------------------ drawing

	public void render(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		boolean hover = dragging || Render2D.hovered(mouseX, mouseY, x, y, width, height);
		Render2D.rect(g, x, y, width, height, Theme.borderDark);
		Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, ColorUtil.withAlpha(Theme.surface, 200));

		setUpCamera(x + 2, y + 2, width - 4, height - 14);
		prims.clear();
		onTop.clear();
		labels.clear();
		var modules = UnluckyClient.INSTANCE.modules;
		xrayModule = modules.get(XRay.class);
		xray = xrayModule.isEnabled();
		addBlocks();
		Render3D.Canvas canvas = new SceneCanvas();
		Render3D.drawInto(canvas, () -> {
			overlay(modules.get(BlockOutline.class), SELECTED, m -> m.preview(this));
			overlay(modules.get(BreakIndicators.class), BREAKING, m -> m.preview(this));
			overlay(modules.get(HoleESP.class), HOLES.getFirst().pos(), m -> m.preview(this));
			overlay(modules.get(VoidESP.class), VOID, m -> m.preview(this));
			overlay(modules.get(LightOverlay.class), null, m -> m.preview(this));
			overlay(modules.get(Search.class), ORE, this::search);
		});
		prims.sort((a, b) -> Double.compare(a.depth(), b.depth()));
		onTop.sort((a, b) -> Double.compare(a.depth(), b.depth()));
		prims.addAll(onTop);
		Shader shader = modules.get(Shader.class);
		if (shader.isEnabled() && shader.storages.get() && shader.barrels.get()) {
			silhouette(shader, BARREL);
		}

		g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
		submit(g);
		for (Label label : labels) {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(label.x(), label.y());
			pose.scale(label.scale(), label.scale());
			Render2D.text(g, label.text(), -Render2D.width(label.text()) / 2, -Render2D.FONT_HEIGHT, label.color());
			pose.popMatrix();
		}
		g.disableScissor();
		Render2D.textNoShadow(g, HINT, x + (width - Render2D.width(HINT)) / 2, y + height - 10,
				hover ? Theme.text : Theme.textDim);
	}

	private <M extends Module> void overlay(M module, BlockPos cell, java.util.function.Consumer<M> draw) {
		if (module.isEnabled()) {
			anchor = cell;
			draw.accept(module);
			anchor = null;
		}
	}

	/** Search keeps its result list private to the world scan, so its preview lives here. */
	private void search(Search search) {
		for (Map.Entry<BlockPos, BlockState> entry : BLOCKS.entrySet()) {
			if (!search.blocks.contains(entry.getValue().getBlock())) {
				continue;
			}
			BlockPos pos = entry.getKey();
			anchor = pos;
			int fill = search.fill.get() ? ColorUtil.withAlpha(search.color.get(), search.fillOpacity.getInt()) : 0;
			Render3D.box(new AABB(pos), search.color.get() | 0xFF000000, 2.0f, fill, search.throughWalls.get());
			if (search.labels.get()) {
				Render3D.blockLabel(entry.getValue().getBlock().getName().getString(), pos, search.color.get(),
						search.labelScale.getFloat());
			}
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
		// the floor's diagonal across, and its depth plus a block of height up
		scale = (float) Math.min(vw / 7.6, vh / (7.1 * sp + 2.4 * cp));
		originX = vx + vw / 2.0f;
		originY = vy + vh / 2.0f + (float) (0.4 * cp) * scale;
	}

	private float screenX(double px, double pz) {
		return originX + (float) ((px - CENTER_X) * rx + (pz - CENTER_Z) * rz) * scale;
	}

	private float screenY(double px, double py, double pz) {
		return originY - (float) ((px - CENTER_X) * ux + (py - CENTER_Y) * uy + (pz - CENTER_Z) * uz) * scale;
	}

	private double depth(double px, double py, double pz) {
		return (px - CENTER_X) * ex + (py - CENTER_Y) * ey + (pz - CENTER_Z) * ez;
	}

	private double cellDepth(BlockPos pos) {
		return depth(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	private boolean ghost(BlockState state) {
		return xray && !xrayModule.blocks.contains(state.getBlock());
	}

	private void addBlocks() {
		RandomSource random = RandomSource.create(42L);
		List<BlockStateModelPart> parts = new ArrayList<>();
		var modelSet = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		for (Map.Entry<BlockPos, BlockState> entry : BLOCKS.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockState state = entry.getValue();
			boolean ghost = ghost(state);
			int alpha = ghost ? 40 : 255;
			double cell = cellDepth(pos);
			parts.clear();
			random.setSeed(42L);
			modelSet.get(state).collectParts(random, parts);
			for (BlockStateModelPart part : parts) {
				for (Direction side : Direction.values()) {
					BlockState neighbour = getBlockState(pos.relative(side));
					// hidden against a solid neighbour; ghosts only hide against each other
					boolean covered = !neighbour.isAir() && ghost(neighbour) == ghost;
					if (!covered) {
						addQuads(part.getQuads(side), pos, alpha, cell);
					}
				}
				addQuads(part.getQuads(null), pos, alpha, cell);
			}
		}
	}

	private void addQuads(List<BakedQuad> quads, BlockPos pos, int alpha, double cell) {
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
			float shade = switch (facing) {
				case UP -> 1.0f;
				case DOWN -> 0.5f;
				case NORTH, SOUTH -> 0.8f;
				default -> 0.6f;
			};
			int tint = quad.materialInfo().isTinted() ? GRASS_TINT : 0xFFFFFFFF;
			int color = ColorUtil.argb(alpha, (int) (((tint >> 16) & 0xFF) * shade),
					(int) (((tint >> 8) & 0xFF) * shade), (int) ((tint & 0xFF) * shade));
			prims.add(new Prim(xy, uv, color, cell));
		}
	}

	/** Where an overlay sorts: just after the cell it belongs to. */
	private double overlayDepth(Vec3 centroid) {
		BlockPos cell = anchor != null ? anchor
				: BlockPos.containing(centroid.x, centroid.y + 0.01, centroid.z);
		return cellDepth(cell) + 0.01;
	}

	private void addOverlay(float[] xy, int argb, Vec3 centroid, boolean throughWalls) {
		if (ColorUtil.alpha(argb) == 0) {
			return;
		}
		Prim prim = new Prim(xy, null, argb, throughWalls
				? depth(centroid.x, centroid.y, centroid.z)
				: overlayDepth(centroid));
		(throughWalls ? onTop : prims).add(prim);
	}

	/** A screen-space strip between two projected points. */
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

	/**
	 * Shader's storage silhouette: the barrel's projected outline, which for a box is the convex
	 * hull of its corners. Drawn last, over everything, as the post pass is.
	 */
	private void silhouette(Shader shader, BlockPos pos) {
		int color = shader.tint(shader.barrelColor.get(), Vec3.atCenterOf(pos));
		if (color == 0) {
			return;
		}
		float[][] corners = new float[8][];
		for (int i = 0; i < 8; i++) {
			double cx = pos.getX() + (i & 1), cy = pos.getY() + ((i >> 1) & 1), cz = pos.getZ() + ((i >> 2) & 1);
			corners[i] = new float[] {screenX(cx, cz), screenY(cx, cy, cz)};
		}
		List<float[]> hull = hull(corners);
		float fill = shader.fillOpacity();
		if (fill > 0.0f) {
			int fillColor = ColorUtil.multiplyAlpha(color, Math.min(1.0f, fill * 4.0f));
			for (int i = 1; i + 1 < hull.size(); i++) {
				float[] a = hull.getFirst(), b = hull.get(i), c = hull.get(i + 1);
				prims.add(new Prim(new float[] {a[0], a[1], b[0], b[1], c[0], c[1], c[0], c[1]}, null, fillColor, 0));
			}
		}
		boolean glow = shader.mode.is("Glow");
		int rings = glow ? 4 : 1;
		for (int ring = rings; ring >= 1; ring--) {
			float width = glow ? shader.widthTexels() * 2.0f * ring / rings : shader.widthTexels() * 1.5f;
			int ringColor = glow ? ColorUtil.multiplyAlpha(color, 0.35f / ring + 0.1f) : color;
			for (int i = 0; i < hull.size(); i++) {
				float[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
				float[] xy = strip(a[0], a[1], b[0], b[1], width);
				if (xy != null) {
					prims.add(new Prim(xy, null, ringColor, 0));
				}
			}
		}
	}

	/** Andrew's monotone chain. */
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

	/** Consecutive primitives with the same texturing go out as one element, in order. */
	private void submit(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		TextureSetup textured = TextureSetup.singleTexture(atlas.getTextureView(), atlas.getSampler());
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle area = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
		if (bounds == null) {
			return;
		}
		int start = 0;
		for (int i = 1; i <= prims.size(); i++) {
			if (i == prims.size() || (prims.get(i).uv() != null) != (prims.get(start).uv() != null)) {
				boolean isTextured = prims.get(start).uv() != null;
				g.guiRenderState.addGuiElement(new Batch(
						isTextured ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI,
						isTextured ? textured : TextureSetup.noTexture(),
						pose, List.copyOf(prims.subList(start, i)), scissor, bounds));
				start = i;
			}
		}
	}

	private record Batch(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, List<Prim> quads,
			ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer consumer) {
			for (Prim quad : quads) {
				for (int i = 0; i < 4; i++) {
					VertexConsumer vertex = consumer.addVertexWith2DPose(pose, quad.xy()[i * 2], quad.xy()[i * 2 + 1]);
					if (quad.uv() != null) {
						vertex = vertex.setUv(quad.uv()[i * 2], quad.uv()[i * 2 + 1]);
					}
					vertex.setColor(quad.color());
				}
			}
		}
	}

	/** Where {@link Render3D} lands while the modules draw their previews. */
	private final class SceneCanvas implements Render3D.Canvas {
		@Override
		public Vec3 eye() {
			return new Vec3(CENTER_X + ex * 100.0, CENTER_Y + ey * 100.0, CENTER_Z + ez * 100.0);
		}

		@Override
		public boolean solid(BlockPos pos) {
			return !getBlockState(pos).isAir();
		}

		@Override
		public void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb, boolean throughWalls) {
			float[] xy = {screenX(a.x, a.z), screenY(a.x, a.y, a.z), screenX(b.x, b.z), screenY(b.x, b.y, b.z),
					screenX(c.x, c.z), screenY(c.x, c.y, c.z), screenX(d.x, d.z), screenY(d.x, d.y, d.z)};
			addOverlay(xy, argb, a.add(b).add(c).add(d).scale(0.25), throughWalls);
		}

		@Override
		public void line(Vec3 a, Vec3 b, int argb, float width, boolean throughWalls) {
			float[] xy = strip(screenX(a.x, a.z), screenY(a.x, a.y, a.z), screenX(b.x, b.z), screenY(b.x, b.y, b.z),
					width);
			if (xy != null) {
				addOverlay(xy, argb, a.add(b).scale(0.5), throughWalls);
			}
		}

		@Override
		public void label(String text, Vec3 at, int argb, float labelScale) {
			labels.add(new Label(text, screenX(at.x, at.z), screenY(at.x, at.y, at.z), argb | 0xFF000000,
					0.75f * labelScale));
		}
	}

	// ------------------------------------------------------------------ input

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || !Render2D.hovered(mouseX, mouseY, x, y, width, height)) {
			return false;
		}
		dragging = true;
		lastX = mouseX;
		lastY = mouseY;
		return true;
	}

	public void mouseDragged(double mouseX, double mouseY) {
		if (dragging) {
			yaw = Mth.wrapDegrees(yaw + (float) (mouseX - lastX) * 1.6f);
			pitch = Mth.clamp(pitch + (float) (mouseY - lastY) * 0.8f, 12.0f, 75.0f);
			lastX = mouseX;
			lastY = mouseY;
		}
	}

	public void mouseReleased() {
		dragging = false;
	}
}
