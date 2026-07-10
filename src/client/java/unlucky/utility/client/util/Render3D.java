package unlucky.utility.client.util;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * World-space ESP drawing built on the vanilla gizmo system, plus
 * world-to-screen projection for 2D overlays.
 *
 * Gizmo calls only work while a collector is active — in practice that means
 * call them from module {@code onTick} (the client tick runs inside one).
 * Gizmos live for one tick and must be re-emitted every tick.
 */
public final class Render3D {
	private Render3D() {
	}

	/**
	 * Box with outline and/or fill; pass 0 for either color to skip it.
	 * Outlines only draw the edges facing the camera — no back lines.
	 */
	public static void box(AABB box, int outlineArgb, float lineWidth, int fillArgb, boolean throughWalls) {
		box(box, outlineArgb, lineWidth, fillArgb, throughWalls, java.util.List.of());
	}

	/**
	 * Box whose outline edges are additionally hidden when they sit behind one
	 * of the {@code occluders} (other ESP boxes) from the camera's view.
	 */
	public static void box(AABB box, int outlineArgb, float lineWidth, int fillArgb, boolean throughWalls,
			java.util.List<AABB> occluders) {
		if (fillArgb == 0 && outlineArgb == 0) {
			return;
		}
		Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
		if (fillArgb != 0) {
			GizmoStyle style = GizmoStyle.fill(fillArgb);
			visibleFillGeometry(box, eye, occluders,
					(a, b, c, d) -> face(a, b, c, d, style, throughWalls),
					() -> {
						GizmoProperties properties = Gizmos.cuboid(box, style);
						if (throughWalls) {
							properties.setAlwaysOnTop();
						}
					});
		}
		if (outlineArgb != 0) {
			visibleEdgesGeometry(box, eye, occluders, (a, b) -> line(a, b, outlineArgb, lineWidth, throughWalls));
		}
	}

	/** Where a computed fill quad ends up: drawn immediately, or recorded into a {@link BoxGeom}. */
	@FunctionalInterface
	private interface QuadSink {
		void accept(Vec3 a, Vec3 b, Vec3 c, Vec3 d);
	}

	/** Where a computed outline segment ends up: drawn immediately, or recorded into a {@link BoxGeom}. */
	@FunctionalInterface
	private interface LineSink {
		void accept(Vec3 a, Vec3 b);
	}

	/**
	 * Clipped, eye-relative shape of one ESP box, as computed by
	 * {@link #computeGeometry} — draw it (possibly many times, with a fresh
	 * color/alpha each time) via {@link #emitGeometry}.
	 */
	public static final class BoxGeom {
		private final java.util.List<Vec3[]> quads = new java.util.ArrayList<>();
		private final java.util.List<Vec3[]> lines = new java.util.ArrayList<>();
		private boolean cuboidFallback;

		/** Fill quads (4 corners each), in the order they'd be drawn. Read-only. */
		public java.util.List<Vec3[]> quads() {
			return quads;
		}

		/** Outline segments (2 endpoints each), in the order they'd be drawn. Read-only. */
		public java.util.List<Vec3[]> lines() {
			return lines;
		}

		/** True when the fill fell back to a single solid cuboid (camera inside the box). */
		public boolean cuboidFallback() {
			return cuboidFallback;
		}
	}

	/**
	 * Computes (but does not draw) the same clipped fill/outline shape {@link #box}
	 * would draw for the given eye position, appending into {@code out} (or a new
	 * {@link BoxGeom} if {@code out} is null) for cheap repeated drawing via
	 * {@link #emitGeometry}. Both the fill quads and the outline segments are
	 * always computed, regardless of which colors will actually be used at emit
	 * time — the shape only depends on {@code box}/{@code eye}/{@code occluders},
	 * so recomputation is only needed when one of those changes (e.g. the camera
	 * moves), not when a color/fill/outline toggle changes.
	 */
	public static BoxGeom computeGeometry(AABB box, Vec3 eye, java.util.List<AABB> occluders, BoxGeom out) {
		BoxGeom geom = out != null ? out : new BoxGeom();
		geom.quads.clear();
		geom.lines.clear();
		geom.cuboidFallback = false;
		visibleFillGeometry(box, eye, occluders,
				(a, b, c, d) -> geom.quads.add(new Vec3[] {a, b, c, d}),
				() -> geom.cuboidFallback = true);
		visibleEdgesGeometry(box, eye, occluders, (a, b) -> geom.lines.add(new Vec3[] {a, b}));
		return geom;
	}

	/** Draws geometry previously computed by {@link #computeGeometry}, with the given style. */
	public static void emitGeometry(BoxGeom geom, AABB box, int outlineArgb, float lineWidth, int fillArgb,
			boolean throughWalls) {
		if (fillArgb != 0) {
			GizmoStyle style = GizmoStyle.fill(fillArgb);
			if (geom.cuboidFallback) {
				GizmoProperties properties = Gizmos.cuboid(box, style);
				if (throughWalls) {
					properties.setAlwaysOnTop();
				}
			} else {
				for (Vec3[] q : geom.quads) {
					face(q[0], q[1], q[2], q[3], style, throughWalls);
				}
			}
		}
		if (outlineArgb != 0) {
			for (Vec3[] l : geom.lines) {
				line(l[0], l[1], outlineArgb, lineWidth, throughWalls);
			}
		}
	}

	// face order: 0 west, 1 east, 2 down, 3 up, 4 north, 5 south
	private static final Vec3[] NORMALS = {
			new Vec3(-1, 0, 0), new Vec3(1, 0, 0), new Vec3(0, -1, 0),
			new Vec3(0, 1, 0), new Vec3(0, 0, -1), new Vec3(0, 0, 1)
	};

	/** Which faces point at the camera (downVisible handles the ground rule). */
	private static boolean[] faceVisibility(AABB b, Vec3 eye) {
		boolean west = eye.x < b.minX, east = eye.x > b.maxX;
		boolean down = eye.y < b.minY, up = eye.y > b.maxY;
		boolean north = eye.z < b.minZ, south = eye.z > b.maxZ;
		// on solid ground the bottom face reads as hidden when peeking from
		// near its plane, but from well below (mining under it) show it fully
		boolean downVisible = down && (!onGround(b) || eye.y < b.minY - 0.75);
		return new boolean[] {west, east, downVisible, up, north, south};
	}

	/**
	 * True when a point just off a face sits inside another ESP box. Tight
	 * tolerance on purpose: welding (StorageESP) makes true neighbors touch
	 * exactly, so anything not welded — like the exposed step where an inset
	 * chest meets a full block — correctly keeps its edges.
	 */
	private static boolean covered(Vec3 point, AABB self, java.util.List<AABB> occluders) {
		for (AABB box : occluders) {
			if (box != self && containsExpanded(box, 0.02, point.x, point.y, point.z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code box.inflate(expand).contains(x,y,z)} without allocating the inflated
	 * AABB. Upper bounds are exclusive to match vanilla AABB.contains(double,double,
	 * double) exactly (its bytecode rejects x >= maxX, not x > maxX).
	 */
	private static boolean containsExpanded(AABB box, double expand, double x, double y, double z) {
		return x >= box.minX - expand && x < box.maxX + expand
				&& y >= box.minY - expand && y < box.maxY + expand
				&& z >= box.minZ - expand && z < box.maxZ + expand;
	}

	/**
	 * Fill drawn as individual faces: only faces pointing at the camera and not
	 * pressed against another ESP box (touching fills would double up). Reports
	 * each visible quad to {@code sink}, or calls {@code cuboidFallback} once if
	 * the camera is inside the box and no face produced anything. Pure geometry —
	 * no drawing here — shared by the immediate-draw path ({@link #box}) and the
	 * cached path ({@link #computeGeometry}).
	 */
	private static void visibleFillGeometry(AABB b, Vec3 eye, java.util.List<AABB> occluders,
			QuadSink sink, Runnable cuboidFallback) {
		Vec3 aaa = new Vec3(b.minX, b.minY, b.minZ), baa = new Vec3(b.maxX, b.minY, b.minZ);
		Vec3 aba = new Vec3(b.minX, b.maxY, b.minZ), bba = new Vec3(b.maxX, b.maxY, b.minZ);
		Vec3 aab = new Vec3(b.minX, b.minY, b.maxZ), bab = new Vec3(b.maxX, b.minY, b.maxZ);
		Vec3 abb = new Vec3(b.minX, b.maxY, b.maxZ), bbb = new Vec3(b.maxX, b.maxY, b.maxZ);

		Vec3[][] faces = {
				{aaa, aab, abb, aba}, // west
				{baa, bab, bbb, bba}, // east
				{aaa, baa, bab, aab}, // down
				{aba, bba, bbb, abb}, // up
				{aaa, aba, bba, baa}, // north
				{aab, abb, bbb, bab}  // south
		};
		boolean[] visible = faceVisibility(b, eye);
		boolean any = false;
		for (int f = 0; f < 6; f++) {
			if (!visible[f]) {
				continue;
			}
			Vec3 center = faces[f][0].add(faces[f][2]).scale(0.5);
			if (covered(center.add(NORMALS[f].scale(0.05)), b, occluders)) {
				continue;
			}
			if (drawFaceClipped(faces[f], center, eye, b, occluders, sink)) {
				any = true;
			}
		}
		if (!any && b.inflate(0.1).contains(eye)) {
			// camera inside the box: fall back to the plain cuboid fill
			cuboidFallback.run();
		}
	}

	private static void face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, GizmoStyle style, boolean throughWalls) {
		GizmoProperties properties = Gizmos.rect(a, b, c, d, style);
		if (throughWalls) {
			properties.setAlwaysOnTop();
		}
	}

	/**
	 * Draws a fill face clipped against the other ESP boxes: fully visible faces
	 * draw as one quad, fully hidden ones are skipped, and partially hidden ones
	 * are cut into a 4x4 grid with only the visible cells drawn (row-merged).
	 */
	private static boolean drawFaceClipped(Vec3[] q, Vec3 center, Vec3 eye, AABB self,
			java.util.List<AABB> occluders, QuadSink sink) {
		if (occluders.isEmpty()) {
			sink.accept(q[0], q[1], q[2], q[3]);
			return true;
		}
		int visibleProbes = 0;
		Vec3[] probes = {center, q[0].lerp(center, 0.15), q[1].lerp(center, 0.15),
				q[2].lerp(center, 0.15), q[3].lerp(center, 0.15)};
		for (Vec3 probe : probes) {
			if (!pointBlocked(probe, eye, self, occluders)) {
				visibleProbes++;
			}
		}
		if (visibleProbes == 5) {
			sink.accept(q[0], q[1], q[2], q[3]);
			return true;
		}
		if (visibleProbes == 0) {
			return false;
		}
		boolean drew = false;
		final int grid = 4;
		for (int row = 0; row < grid; row++) {
			int runStart = -1;
			for (int col = 0; col <= grid; col++) {
				boolean cellVisible = col < grid
						&& !pointBlocked(quadPoint(q, (col + 0.5) / grid, (row + 0.5) / grid), eye, self, occluders);
				if (cellVisible && runStart < 0) {
					runStart = col;
				} else if (!cellVisible && runStart >= 0) {
					sink.accept(quadPoint(q, (double) runStart / grid, (double) row / grid),
							quadPoint(q, (double) col / grid, (double) row / grid),
							quadPoint(q, (double) col / grid, (double) (row + 1) / grid),
							quadPoint(q, (double) runStart / grid, (double) (row + 1) / grid));
					drew = true;
					runStart = -1;
				}
			}
		}
		return drew;
	}

	/** Bilinear point on a quad given in loop order. */
	private static Vec3 quadPoint(Vec3[] q, double u, double v) {
		return q[0].lerp(q[1], u).lerp(q[3].lerp(q[2], u), v);
	}

	private static boolean pointBlocked(Vec3 sample, Vec3 eye, AABB self, java.util.List<AABB> occluders) {
		for (AABB box : occluders) {
			if (box != self && blocked(box, eye, sample)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reports the edges you could actually see of a solid box: an edge shows only
	 * when one of its faces points at the camera AND that face isn't pressed
	 * against a neighboring ESP box, and the edge isn't hidden behind one. Pure
	 * geometry — shared by the immediate-draw path ({@link #box}) and the cached
	 * path ({@link #computeGeometry}).
	 */
	private static void visibleEdgesGeometry(AABB b, Vec3 eye, java.util.List<AABB> occluders, LineSink sink) {
		// camera inside the box: nothing "faces" us, draw everything
		boolean all = b.inflate(0.1).contains(eye);

		Vec3 aaa = new Vec3(b.minX, b.minY, b.minZ), baa = new Vec3(b.maxX, b.minY, b.minZ);
		Vec3 aba = new Vec3(b.minX, b.maxY, b.minZ), bba = new Vec3(b.maxX, b.maxY, b.minZ);
		Vec3 aab = new Vec3(b.minX, b.minY, b.maxZ), bab = new Vec3(b.maxX, b.minY, b.maxZ);
		Vec3 abb = new Vec3(b.minX, b.maxY, b.maxZ), bbb = new Vec3(b.maxX, b.maxY, b.maxZ);

		Vec3[][] edges = {
				{aaa, baa}, {aab, bab}, {aaa, aab}, {baa, bab}, // bottom ring
				{aba, bba}, {abb, bbb}, {aba, abb}, {bba, bbb}, // top ring
				{aaa, aba}, {baa, bba}, {aab, abb}, {bab, bbb}  // verticals
		};
		// the two faces each edge borders (indices into NORMALS order)
		int[][] edgeFaces = {
				{2, 4}, {2, 5}, {2, 0}, {2, 1},
				{3, 4}, {3, 5}, {3, 0}, {3, 1},
				{4, 0}, {4, 1}, {5, 0}, {5, 1}
		};
		boolean[] visible = faceVisibility(b, eye);
		for (int i = 0; i < 12; i++) {
			Vec3 mid = edges[i][0].add(edges[i][1]).scale(0.5);
			boolean show = all;
			for (int f : edgeFaces[i]) {
				if (visible[f] && !covered(mid.add(NORMALS[f].scale(0.05)), b, occluders)) {
					show = true;
					break;
				}
			}
			if (show) {
				drawVisibleRuns(edges[i][0], edges[i][1], eye, b, occluders, sink);
			}
		}
	}

	/**
	 * Reports only the visible stretches of an edge: the segment is split into
	 * steps and stretches hidden behind other ESP boxes are cut out, so a
	 * partially covered edge is clipped instead of vanishing entirely.
	 */
	private static void drawVisibleRuns(Vec3 start, Vec3 end, Vec3 eye, AABB self,
			java.util.List<AABB> occluders, LineSink sink) {
		if (occluders.isEmpty()) {
			sink.accept(start, end);
			return;
		}
		final int steps = 8;
		Vec3 center = self.getCenter();
		int runStart = -1;
		for (int s = 0; s <= steps; s++) {
			boolean visible = false;
			if (s < steps) {
				// nudge the sample toward the body so grazing rays can't slip past
				Vec3 sample = start.lerp(end, (s + 0.5) / steps).lerp(center, 0.08);
				visible = true;
				for (AABB box : occluders) {
					if (box != self && blocked(box, eye, sample)) {
						visible = false;
						break;
					}
				}
			}
			if (visible && runStart < 0) {
				runStart = s;
			} else if (!visible && runStart >= 0) {
				sink.accept(start.lerp(end, (double) runStart / steps), start.lerp(end, (double) s / steps));
				runStart = -1;
			}
		}
	}

	/** True when the box rests on solid blocks. */
	private static boolean onGround(AABB b) {
		var level = Minecraft.getInstance().level;
		if (level == null) {
			return false;
		}
		BlockPos below = BlockPos.containing(b.getCenter().x, b.minY - 0.5, b.getCenter().z);
		return level.getBlockState(below).isSolidRender();
	}

	/**
	 * The ray is blocked when it enters the box far from the sample: entering
	 * right next to the sample just means the sample sits on this box's own
	 * surface (or a shared face) and is directly visible.
	 *
	 * Equivalent to {@code box.clip(eye, sample)} but allocation-free — see
	 * {@link #slabEntry} for the derivation against vanilla's AABB.clip.
	 */
	private static boolean blocked(AABB box, Vec3 eye, Vec3 sample) {
		double dx = sample.x - eye.x, dy = sample.y - eye.y, dz = sample.z - eye.z;
		double t = slabEntry(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.0,
				eye.x, eye.y, eye.z, dx, dy, dz);
		if (t < 0.0) {
			return false;
		}
		double hx = eye.x + t * dx - sample.x;
		double hy = eye.y + t * dy - sample.y;
		double hz = eye.z + t * dz - sample.z;
		return hx * hx + hy * hy + hz * hz > 0.09;
	}

	/** Whether the segment eye->sample enters {@code box} shrunk by {@code deflate}. */
	private static boolean entersDeflated(AABB box, double deflate, Vec3 eye, Vec3 sample) {
		double dx = sample.x - eye.x, dy = sample.y - eye.y, dz = sample.z - eye.z;
		return slabEntry(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, -deflate,
				eye.x, eye.y, eye.z, dx, dy, dz) >= 0.0;
	}

	/** Direction components at or below this magnitude are treated as "no movement" on that axis. */
	private static final double DIR_EPS = 1.0e-7;

	/**
	 * Standard AABB slab test: entry parameter t in (0,1] of the segment
	 * (fx,fy,fz) -> (fx+dx,fy+dy,fz+dz) against the box (grown by {@code expand}
	 * on every side; pass a negative value to shrink it), or -1 for a miss.
	 *
	 * Deliberately mirrors {@code net.minecraft.world.phys.AABB#clip}, which
	 * (per its decompiled bytecode) returns {@code Optional.empty()} both on a
	 * genuine miss AND when the segment starts inside the box — starting inside
	 * makes every per-axis entry candidate <= 0, which this implementation
	 * reproduces by clamping tmin to 0 and requiring a strictly positive result.
	 * Verified bit-for-bit against {@code AABB.clip} by the equivalence harness
	 * (see plan.md Phase 1 / "Verification").
	 */
	private static double slabEntry(double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ, double expand,
			double fx, double fy, double fz, double dx, double dy, double dz) {
		minX -= expand;
		minY -= expand;
		minZ -= expand;
		maxX += expand;
		maxY += expand;
		maxZ += expand;

		double tmin = 0.0, tmax = 1.0;

		if (dx > DIR_EPS || dx < -DIR_EPS) {
			double t1 = (minX - fx) / dx, t2 = (maxX - fx) / dx;
			if (t1 > t2) {
				double tmp = t1;
				t1 = t2;
				t2 = tmp;
			}
			if (t1 > tmin) {
				tmin = t1;
			}
			if (t2 < tmax) {
				tmax = t2;
			}
		} else if (fx < minX || fx > maxX) {
			return -1.0;
		}

		if (dy > DIR_EPS || dy < -DIR_EPS) {
			double t1 = (minY - fy) / dy, t2 = (maxY - fy) / dy;
			if (t1 > t2) {
				double tmp = t1;
				t1 = t2;
				t2 = tmp;
			}
			if (t1 > tmin) {
				tmin = t1;
			}
			if (t2 < tmax) {
				tmax = t2;
			}
		} else if (fy < minY || fy > maxY) {
			return -1.0;
		}

		if (dz > DIR_EPS || dz < -DIR_EPS) {
			double t1 = (minZ - fz) / dz, t2 = (maxZ - fz) / dz;
			if (t1 > t2) {
				double tmp = t1;
				t1 = t2;
				t2 = tmp;
			}
			if (t1 > tmin) {
				tmin = t1;
			}
			if (t2 < tmax) {
				tmax = t2;
			}
		} else if (fz < minZ || fz > maxZ) {
			return -1.0;
		}

		if (tmin > tmax || tmin <= 0.0) {
			return -1.0;
		}
		return tmin;
	}

	public static void blockBox(BlockPos pos, int outlineArgb, float lineWidth, int fillArgb, boolean throughWalls) {
		box(new AABB(pos).deflate(0.02), outlineArgb, lineWidth, fillArgb, throughWalls);
	}

	/**
	 * True when the target box is fully hidden behind other ESP boxes
	 * (inter-ESP culling — terrain never blocks). Samples the center and all
	 * eight corners; the box is culled only when every sightline is blocked,
	 * so partially visible boxes stay on screen.
	 */
	public static boolean occluded(AABB target, Vec3 eye, java.util.List<AABB> occluders) {
		AABB inset = target.deflate(Math.min(0.12, target.getXsize() * 0.25));
		Vec3[] samples = {
				target.getCenter(),
				new Vec3(inset.minX, inset.minY, inset.minZ), new Vec3(inset.maxX, inset.minY, inset.minZ),
				new Vec3(inset.minX, inset.maxY, inset.minZ), new Vec3(inset.maxX, inset.maxY, inset.minZ),
				new Vec3(inset.minX, inset.minY, inset.maxZ), new Vec3(inset.maxX, inset.minY, inset.maxZ),
				new Vec3(inset.minX, inset.maxY, inset.maxZ), new Vec3(inset.maxX, inset.maxY, inset.maxZ)
		};
		outer:
		for (Vec3 sample : samples) {
			for (AABB box : occluders) {
				if (box != target && entersDeflated(box, 0.05, eye, sample)) {
					continue outer; // this sightline is blocked, try the next
				}
			}
			return false; // at least one sightline is clear — visible
		}
		return true; // every sightline blocked
	}

	public static void line(Vec3 start, Vec3 end, int argb, float width, boolean throughWalls) {
		GizmoProperties properties = Gizmos.line(start, end, argb, width);
		if (throughWalls) {
			properties.setAlwaysOnTop();
		}
	}

	/** Floating billboard label over a block, always on top. */
	public static void blockLabel(String text, BlockPos pos, int argb, float scale) {
		Gizmos.billboardTextOverBlock(text, pos, 0, argb, scale);
	}

	/**
	 * Projects a world position to GUI-scaled screen coordinates.
	 * Returns null when the point is behind the camera.
	 * The returned z component is the view-space depth.
	 */
	public static Vec3 worldToScreen(Vec3 world, int guiWidth, int guiHeight) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vec3 relative = world.subtract(camera.position());
		Vector4f clip = viewProjection.transform(
				new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0f));
		if (clip.w() < 0.05f) {
			return null;
		}
		double x = (clip.x() / clip.w() + 1.0) / 2.0 * guiWidth;
		double y = (1.0 - clip.y() / clip.w()) / 2.0 * guiHeight;
		return new Vec3(x, y, clip.w());
	}
}
