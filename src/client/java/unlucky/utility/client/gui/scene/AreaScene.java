package unlucky.utility.client.gui.scene;

import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * An {@link AreaSource}'s box drawn as blocks: finished cells solid, cells still to place as
 * see-through ghosts of what goes there, cells still to dig as red-tinted ghosts of what is in
 * the way, and the block being worked on glowing.
 *
 * <p>The world is read into a grid every {@value #RESCAN_MS} ms (or when the box changes), not
 * every frame. A box wider than {@value #WINDOW} blocks on a side is shown as the
 * {@value #WINDOW}-block window around you; the popup says so.
 *
 * <p>When the source lets its corners be edited they get handles: dragging one moves that
 * corner along whichever axis the drag follows best on screen, a block at a time.
 */
public final class AreaScene {
	private static final int WINDOW = 48;
	private static final long RESCAN_MS = 500;
	private static final byte NONE = 0, DONE = 1, PLACE = 2, WRONG = 3, DIG = 4;

	private final SceneCanvas canvas = new SceneCanvas();
	private AreaSource source;

	private BlockPos[] scannedBounds;
	private long scannedAt;
	private int minX, minY, minZ, sx, sy, sz;
	private byte[] kinds;
	private BlockState[] states;
	private boolean clipped;
	private int done, left;

	private final List<float[]>[] handles = newHandles();
	private int grabbed = -1;
	private BlockPos[] grabCorners;
	private double grabX, grabY;

	@SuppressWarnings("unchecked")
	private static List<float[]>[] newHandles() {
		return new List[2];
	}

	public void open(AreaSource source) {
		this.source = source;
		scannedAt = 0;
		kinds = null;
		grabbed = -1;
		source.refresh();
		BlockPos[] bounds = source.bounds();
		if (bounds == null) {
			canvas.reset(0, 0, 0);
			return;
		}
		canvas.reset((bounds[0].getX() + bounds[1].getX() + 1) / 2.0, (bounds[0].getY() + bounds[1].getY() + 1) / 2.0,
				(bounds[0].getZ() + bounds[1].getZ() + 1) / 2.0);
	}

	public int done() {
		return done;
	}

	public int left() {
		return left;
	}

	public boolean clipped() {
		return clipped;
	}

	// ------------------------------------------------------------------ scanning

	private void scan(Level level) {
		source.refresh();
		BlockPos[] bounds = source.bounds();
		scannedBounds = bounds;
		scannedAt = System.currentTimeMillis();
		done = 0;
		left = 0;
		if (bounds == null) {
			kinds = null;
			return;
		}
		Player player = Minecraft.getInstance().player;
		BlockPos centre = player != null ? player.blockPosition()
				: new BlockPos((bounds[0].getX() + bounds[1].getX()) / 2, (bounds[0].getY() + bounds[1].getY()) / 2,
						(bounds[0].getZ() + bounds[1].getZ()) / 2);
		int[] x = window(bounds[0].getX(), bounds[1].getX(), centre.getX());
		int[] y = window(bounds[0].getY(), bounds[1].getY(), centre.getY());
		int[] z = window(bounds[0].getZ(), bounds[1].getZ(), centre.getZ());
		clipped = x[2] + y[2] + z[2] > 0;
		minX = x[0];
		minY = y[0];
		minZ = z[0];
		sx = x[1] - x[0] + 1;
		sy = y[1] - y[0] + 1;
		sz = z[1] - z[0] + 1;
		kinds = new byte[sx * sy * sz];
		states = new BlockState[kinds.length];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < sx; i++) {
			for (int j = 0; j < sy; j++) {
				for (int k = 0; k < sz; k++) {
					pos.set(minX + i, minY + j, minZ + k);
					BlockState wanted = source.wanted(pos);
					if (wanted == null) {
						continue;
					}
					BlockState world = level.getBlockState(pos);
					int index = index(i, j, k);
					if (wanted.isAir()) {
						if (world.canBeReplaced()) {
							done++;
						} else {
							kinds[index] = DIG;
							states[index] = world;
							left++;
						}
					} else if (world.getBlock() == wanted.getBlock()) {
						kinds[index] = DONE;
						states[index] = world;
						done++;
					} else {
						kinds[index] = world.canBeReplaced() ? PLACE : WRONG;
						states[index] = wanted;
						left++;
					}
				}
			}
		}
	}

	/** {lo, hi, clipped} of one axis, at most {@link #WINDOW} long and around {@code centre}. */
	private static int[] window(int a, int b, int centre) {
		int lo = Math.min(a, b), hi = Math.max(a, b);
		if (hi - lo + 1 <= WINDOW) {
			return new int[] {lo, hi, 0};
		}
		int start = Math.clamp(centre - WINDOW / 2, lo, hi - WINDOW + 1);
		return new int[] {start, start + WINDOW - 1, 1};
	}

	private int index(int i, int j, int k) {
		return (i * sy + j) * sz + k;
	}

	private boolean drawn(int i, int j, int k) {
		return i >= 0 && j >= 0 && k >= 0 && i < sx && j < sy && k < sz && kinds[index(i, j, k)] != NONE;
	}

	// ------------------------------------------------------------------ drawing

	public void render(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
		Render2D.rect(g, x, y, width, height, 0xFF0A0A0D);
		g.outline(x, y, width, height, Theme.border);
		Level level = Minecraft.getInstance().level;
		if (level == null || source == null) {
			Render2D.textNoShadow(g, "Join a world to see the area", x + 8, y + 8, Theme.textDim);
			return;
		}
		long now = System.currentTimeMillis();
		if (scannedAt == 0 || now - scannedAt > RESCAN_MS || !Arrays.equals(source.bounds(), scannedBounds)) {
			scan(level);
		}
		BlockPos[] bounds = scannedBounds;
		if (bounds == null || kinds == null) {
			Render2D.textNoShadow(g, "Nothing to show yet", x + 8, y + 8, Theme.textDim);
			return;
		}

		canvas.begin(x, y, width, height, Math.max(sx, Math.max(sy, sz)) * 0.62 + 1.0);
		for (int i = 0; i < sx; i++) {
			for (int j = 0; j < sy; j++) {
				for (int k = 0; k < sz; k++) {
					byte kind = kinds[index(i, j, k)];
					if (kind == NONE || (drawn(i - 1, j, k) && drawn(i + 1, j, k) && drawn(i, j - 1, k)
							&& drawn(i, j + 1, k) && drawn(i, j, k - 1) && drawn(i, j, k + 1))) {
						continue;
					}
					int fi = i, fj = j, fk = k;
					int alpha = kind == DONE ? 255 : kind == DIG ? 150 : 105;
					int tint = switch (kind) {
						case PLACE -> 0xFFA8D8FF;
						case WRONG -> 0xFFFFB060;
						case DIG -> 0xFFFF7A7A;
						default -> 0xFFFFFFFF;
					};
					canvas.block(states[index(i, j, k)], minX + i, minY + j, minZ + k, alpha, tint,
							side -> drawn(fi + side.getStepX(), fj + side.getStepY(), fk + side.getStepZ()));
				}
			}
		}
		Player player = Minecraft.getInstance().player;
		if (player != null) {
			double px = player.getX(), py = player.getY(), pz = player.getZ();
			canvas.box(px - 0.3, py, pz - 0.3, px + 0.3, py + 1.8, pz + 0.3, 0xFF4E8CFF, Double.NaN);
		}

		canvas.wireframe(bounds[0].getX(), bounds[0].getY(), bounds[0].getZ(), bounds[1].getX() + 1,
				bounds[1].getY() + 1, bounds[1].getZ() + 1, Theme.accent1, 1.5f);
		if (clipped) {
			canvas.wireframe(minX, minY, minZ, minX + sx, minY + sy, minZ + sz, 0x60FFFFFF, 1.0f);
		}
		BlockPos target = source.target();
		if (target != null) {
			float pulse = (float) (0.5 + 0.5 * Math.sin(now / 160.0));
			List<float[]> hull = canvas.hull(target.getX() - 0.04, target.getY() - 0.04, target.getZ() - 0.04,
					target.getX() + 1.04, target.getY() + 1.04, target.getZ() + 1.04);
			canvas.fill(hull, ColorUtil.withAlpha(Theme.accent2, (int) (40 + 60 * pulse)));
			canvas.outline(hull, Theme.accent2, 2.0f);
		}

		BlockPos[] corners = source.corners();
		int hover = corners == null || grabbed >= 0 ? grabbed : handleAt(mouseX, mouseY);
		for (int n = 0; n < 2; n++) {
			handles[n] = null;
			if (corners == null) {
				continue;
			}
			BlockPos c = corners[n];
			handles[n] = canvas.hull(c.getX() + 0.15, c.getY() + 0.15, c.getZ() + 0.15, c.getX() + 0.85,
					c.getY() + 0.85, c.getZ() + 0.85);
			canvas.fill(handles[n], ColorUtil.withAlpha(Theme.accent2, n == hover ? 200 : 110));
			canvas.outline(handles[n], 0xFFFFFFFF, n == hover ? 2.0f : 1.0f);
		}
		canvas.draw(g);
		if (corners != null) {
			for (int n = 0; n < 2; n++) {
				BlockPos c = corners[n];
				String label = String.valueOf(n + 1);
				float lx = canvas.screenX(c.getX() + 0.5, c.getZ() + 0.5);
				float ly = canvas.screenY(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
				if (canvas.contains(lx, ly)) {
					Render2D.textNoShadow(g, label, (int) lx - Render2D.width(label) / 2, (int) ly - 4, 0xFF000000);
				}
			}
		}
	}

	/** The handle under the mouse, nearer one first; -1 for none. */
	private int handleAt(double mouseX, double mouseY) {
		BlockPos[] corners = source == null ? null : source.corners();
		int best = -1;
		double bestDepth = Double.NEGATIVE_INFINITY;
		for (int n = 0; n < 2; n++) {
			if (corners != null && handles[n] != null && SceneCanvas.inside(handles[n], mouseX, mouseY)) {
				BlockPos c = corners[n];
				double depth = canvas.depth(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5);
				if (depth > bestDepth) {
					bestDepth = depth;
					best = n;
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ input

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (source == null || !canvas.contains(mouseX, mouseY)) {
			return false;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && source.corners() != null) {
			int handle = handleAt(mouseX, mouseY);
			if (handle >= 0) {
				grabbed = handle;
				grabCorners = source.corners().clone();
				grabX = mouseX;
				grabY = mouseY;
				return true;
			}
		}
		return canvas.mouseClicked(mouseX, mouseY, button);
	}

	public boolean dragging() {
		return grabbed >= 0 || canvas.dragging();
	}

	public void mouseDragged(double mouseX, double mouseY) {
		if (grabbed < 0) {
			canvas.mouseDragged(mouseX, mouseY);
			return;
		}
		int[] step = canvas.dragAlongAxis(mouseX - grabX, mouseY - grabY);
		Direction.Axis axis = Direction.Axis.values()[step[0]];
		BlockPos[] corners = grabCorners.clone();
		corners[grabbed] = corners[grabbed].relative(axis, step[1]);
		if (!corners[grabbed].equals(source.corners()[grabbed])) {
			source.setCorners(corners[0], corners[1]);
		}
	}

	public void mouseReleased() {
		grabbed = -1;
		canvas.mouseReleased();
	}

	public void mouseScrolled(double amount) {
		canvas.mouseScrolled(amount);
	}
}
