package unlucky.utility.client.util.skinlayers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A baked voxel mesh for one 3D skin layer part (3d-skin-layers recreation —
 * their CustomModelPart + CustomizableCube merged). Geometry is flattened at
 * build time into one {@code float[]}: 23 floats per quad — normal (3) + 4
 * vertices x (pos 3 + uv 2), positions pre-divided by 16 into model units.
 * All posing (the animated part transform + per-part offset) is applied to the
 * {@link PoseStack} by the render layer before {@code submitCustomGeometry}
 * captures it; {@link #writeTo} just streams the baked quads transformed by
 * that captured {@link PoseStack.Pose} straight to a {@link VertexConsumer} —
 * no ModelPart involved, so Sodium/Iris can't rewrite the custom cube shapes.
 */
public final class VoxelMesh {
	/** Non-null but quad-less; used when a skin can't produce a mesh. */
	public static final VoxelMesh EMPTY = new VoxelMesh(List.of());

	private static final int POLY_SIZE = 23;

	private final float[] polygonData;

	VoxelMesh(List<PixelCube> cubes) {
		int polys = 0;
		for (PixelCube cube : cubes) {
			polys += cube.polygonCount;
		}
		polygonData = new float[polys * POLY_SIZE];
		int offset = 0;
		for (PixelCube cube : cubes) {
			for (int i = 0; i < cube.polygonCount; i++) {
				offset = cube.polygons[i].write(polygonData, offset);
			}
		}
	}

	public boolean isEmpty() {
		return polygonData.length == 0;
	}

	/**
	 * Stream the baked quads transformed by an already-posed {@link PoseStack.Pose}
	 * (captured by {@code submitCustomGeometry}) to the consumer.
	 *
	 * @param color ARGB tint (0xFFFFFFFF = untinted)
	 */
	public void writeTo(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color) {
		Matrix4f matrix = pose.pose();
		Matrix3f normalMat = pose.normal();
		Vector3f normal = new Vector3f();
		Vector4f pos = new Vector4f();
		for (int id = 0; id < polygonData.length; id += POLY_SIZE) {
			normal.set(polygonData[id], polygonData[id + 1], polygonData[id + 2]);
			normalMat.transform(normal);
			for (int o = 0; o < 4; o++) {
				int v = id + 3 + o * 5;
				pos.set(polygonData[v], polygonData[v + 1], polygonData[v + 2], 1.0f);
				matrix.transform(pos);
				consumer.addVertex(pos.x(), pos.y(), pos.z(), color,
						polygonData[v + 3], polygonData[v + 4], overlay, light,
						normal.x(), normal.y(), normal.z());
			}
		}
	}

	/**
	 * One texture pixel as a cube: up to 6 single-texel quads, faces hidden where
	 * a neighbouring pixel already covers them, and "corner" quads collapsed to a
	 * triangle (their z-fighting fix where two faces meet across a box edge).
	 */
	static final class PixelCube {
		final Polygon[] polygons = new Polygon[6];
		int polygonCount;

		PixelCube(int u, int v, float x, float y, float z, float size, float texWidth, float texHeight,
				Direction[] hide, Direction[][] hideCorners) {
			float px = x + size;
			float py = y + size;
			float pz = z + size;

			Vertex nnn = new Vertex(x, y, z);
			Vertex pnn = new Vertex(px, y, z);
			Vertex ppn = new Vertex(px, py, z);
			Vertex npn = new Vertex(x, py, z);
			Vertex nnp = new Vertex(x, y, pz);
			Vertex pnp = new Vertex(px, y, pz);
			Vertex ppp = new Vertex(px, py, pz);
			Vertex npp = new Vertex(x, py, pz);

			// map each corner pair to the axis not covered by its two directions
			Direction[][] axisCorner = new Direction[3][]; // indexed by Axis.ordinal()
			for (Direction[] corner : hideCorners) {
				nextAxis:
				for (Direction.Axis axis : Direction.Axis.VALUES) {
					for (Direction dir : corner) {
						if (dir.getAxis() == axis) {
							continue nextAxis;
						}
					}
					axisCorner[axis.ordinal()] = corner;
					break;
				}
			}

			if (visible(Direction.DOWN, hide)) {
				add(new Vertex[] { pnp, nnp, nnn, pnn }, axisCorner[Direction.Axis.Y.ordinal()],
						u, v, texWidth, texHeight, Direction.DOWN);
			}
			if (visible(Direction.UP, hide)) {
				add(new Vertex[] { ppn, npn, npp, ppp }, axisCorner[Direction.Axis.Y.ordinal()],
						u, v, texWidth, texHeight, Direction.UP);
			}
			if (visible(Direction.NORTH, hide)) {
				add(new Vertex[] { pnn, nnn, npn, ppn }, axisCorner[Direction.Axis.Z.ordinal()],
						u, v, texWidth, texHeight, Direction.NORTH);
			}
			if (visible(Direction.SOUTH, hide)) {
				add(new Vertex[] { nnp, pnp, ppp, npp }, axisCorner[Direction.Axis.Z.ordinal()],
						u, v, texWidth, texHeight, Direction.SOUTH);
			}
			if (visible(Direction.WEST, hide)) {
				add(new Vertex[] { nnn, nnp, npp, npn }, axisCorner[Direction.Axis.X.ordinal()],
						u, v, texWidth, texHeight, Direction.WEST);
			}
			if (visible(Direction.EAST, hide)) {
				add(new Vertex[] { pnp, pnn, ppn, ppp }, axisCorner[Direction.Axis.X.ordinal()],
						u, v, texWidth, texHeight, Direction.EAST);
			}
		}

		private void add(Vertex[] vertices, Direction[] corner, int u, int v,
				float texWidth, float texHeight, Direction facing) {
			polygons[polygonCount++] = new Polygon(removeCornerVertex(vertices, corner),
					u, v, u + 1.0f, v + 1.0f, texWidth, texHeight, facing);
		}

		private static boolean visible(Direction face, Direction[] hidden) {
			for (Direction dir : hidden) {
				if (dir == face) {
					return false;
				}
			}
			return true;
		}

		/** Collapse the vertex the corner points at into its neighbour (quad -> tri). */
		private static Vertex[] removeCornerVertex(Vertex[] vertices, Direction[] corner) {
			if (corner == null) {
				return vertices;
			}
			Vertex except = vertices[0];
			for (int i = 1; i < 4; i++) {
				except = furthest(except, vertices[i], corner);
			}
			int index = 0;
			for (int i = 0; i < 4; i++) {
				if (vertices[i] == except) {
					continue;
				}
				vertices[index++] = vertices[i];
			}
			vertices[3] = vertices[2];
			return vertices;
		}

		private static Vertex furthest(Vertex a, Vertex b, Direction[] corner) {
			for (Direction dir : corner) {
				double d = dir.getAxis().choose(a.x - b.x, a.y - b.y, a.z - b.z)
						* dir.getAxisDirection().getStep();
				if (d > 0) {
					return a;
				}
				if (d < 0) {
					return b;
				}
			}
			return a;
		}
	}

	private record Vertex(float x, float y, float z) {
	}

	private static final class Polygon {
		private final Vertex[] vertices;
		private final float[] uvs = new float[8];
		private final Direction facing;

		Polygon(Vertex[] vertices, float minU, float minV, float maxU, float maxV,
				float texWidth, float texHeight, Direction facing) {
			this.vertices = vertices;
			this.facing = facing;
			uvs[0] = maxU / texWidth;
			uvs[1] = minV / texHeight;
			uvs[2] = minU / texWidth;
			uvs[3] = minV / texHeight;
			uvs[4] = minU / texWidth;
			uvs[5] = maxV / texHeight;
			uvs[6] = maxU / texWidth;
			uvs[7] = maxV / texHeight;
		}

		int write(float[] data, int offset) {
			data[offset] = facing.getStepX();
			data[offset + 1] = facing.getStepY();
			data[offset + 2] = facing.getStepZ();
			for (int i = 0; i < 4; i++) {
				int at = offset + 3 + i * 5;
				data[at] = vertices[i].x / 16.0f;
				data[at + 1] = vertices[i].y / 16.0f;
				data[at + 2] = vertices[i].z / 16.0f;
				data[at + 3] = uvs[i * 2];
				data[at + 4] = uvs[i * 2 + 1];
			}
			return offset + POLY_SIZE;
		}
	}
}
