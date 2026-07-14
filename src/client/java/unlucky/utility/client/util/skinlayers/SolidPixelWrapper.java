package unlucky.utility.client.util.skinlayers;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Wraps a 2D skin overlay region around a box as per-pixel voxels — the exact
 * 3d-skin-layers algorithm (their versionless SolidPixelWrapper). For every
 * pixel on every box face: skip transparent pixels, place a 1px cube, then
 * hide each of its side faces that a neighbouring pixel already covers —
 * including neighbours that continue around the box edge onto the adjacent
 * face (their "far neighbour" checks). Pixels on a border whose backside
 * face also has content additionally get a corner marker, which collapses
 * the shared quad into a triangle to kill the edge z-fighting. Solid pixels
 * never hide behind translucent ones (glass-like overlay pixels stay
 * see-through without punching holes).
 *
 * <p>Vanilla-Direction semantics here (their custom enum mapped 1:1): the
 * texture "top" strip is DOWN, front is NORTH, right is WEST.
 */
public final class SolidPixelWrapper {
	private SolidPixelWrapper() {
	}

	private record UV(int u, int v) {
	}

	private record Box(int width, int height, int depth) {
	}

	private record Voxel(int x, int y, int z) {
	}

	/**
	 * @param topPivot       arms/legs pivot at the top, the head at the bottom
	 * @param rotationOffset their magic pivot fudge (head 0.6, arms -2)
	 */
	public static VoxelMesh wrapBox(NativeImage skin, int width, int height, int depth,
			int textureU, int textureV, boolean topPivot, float rotationOffset) {
		List<VoxelMesh.PixelCube> cubes = new ArrayList<>();
		float offX = -width / 2.0f;
		float offY = topPivot ? rotationOffset : -height + rotationOffset;
		float offZ = -depth / 2.0f;
		Box box = new Box(width, height, depth);
		UV texUV = new UV(textureU, textureV);
		try {
			for (Direction face : Direction.values()) {
				UV size = faceSize(box, face);
				for (int u = 0; u < size.u; u++) {
					for (int v = 0; v < size.v; v++) {
						addPixel(skin, cubes, offX, offY, offZ, face, box, new UV(u, v), texUV, size);
					}
				}
			}
		} catch (Exception ex) {
			UnluckyClientMod.LOGGER.error("3D skin layer mesh generation failed", ex);
			return VoxelMesh.EMPTY;
		}
		return new VoxelMesh(cubes);
	}

	private static UV faceSize(Box box, Direction face) {
		if (face == Direction.DOWN || face == Direction.UP) {
			return new UV(box.width, box.depth);
		}
		if (face == Direction.NORTH || face == Direction.SOUTH) {
			return new UV(box.width, box.height);
		}
		return new UV(box.depth, box.height);
	}

	/** Face-local UV -> absolute texture UV in the standard unfolded box layout. */
	private static UV onTexture(UV tex, UV on, Box box, Direction face) {
		return switch (face) {
			case DOWN -> new UV(tex.u + box.depth + on.u, tex.v + on.v);
			case UP -> new UV(tex.u + box.width + box.depth + on.u, tex.v + on.v);
			case NORTH -> new UV(tex.u + box.depth + on.u, tex.v + box.depth + on.v);
			case SOUTH -> new UV(tex.u + box.depth + box.width + box.depth + on.u, tex.v + box.depth + on.v);
			case WEST -> new UV(tex.u + on.u, tex.v + box.depth + on.v);
			case EAST -> new UV(tex.u + box.depth + box.width + on.u, tex.v + box.depth + on.v);
		};
	}

	private static Voxel toVoxel(UV on, Box box, Direction face) {
		return switch (face) {
			case DOWN -> new Voxel(on.u, 0, box.depth - 1 - on.v);
			case UP -> new Voxel(on.u, box.height - 1, box.depth - 1 - on.v);
			case NORTH -> new Voxel(on.u, on.v, 0);
			case SOUTH -> new Voxel(box.width - 1 - on.u, on.v, box.depth - 1);
			case WEST -> new Voxel(0, on.v, box.depth - 1 - on.u);
			case EAST -> new Voxel(box.width - 1, on.v, on.u);
		};
	}

	private static UV toFaceUV(Voxel voxel, Box box, Direction face) {
		return switch (face) {
			case DOWN, UP -> new UV(voxel.x, box.depth - 1 - voxel.z);
			case NORTH -> new UV(voxel.x, voxel.y);
			case SOUTH -> new UV(box.width - 1 - voxel.x, voxel.y);
			case WEST -> new UV(box.depth - 1 - voxel.z, voxel.y);
			case EAST -> new UV(voxel.z, voxel.y);
		};
	}

	private static boolean present(NativeImage skin, UV uv) {
		return skin.getLuminanceOrAlpha(uv.u, uv.v) != 0;
	}

	private static boolean solid(NativeImage skin, UV uv) {
		return skin.getLuminanceOrAlpha(uv.u, uv.v) == -1; // byte 255
	}

	private static void addPixel(NativeImage skin, List<VoxelMesh.PixelCube> cubes,
			float offX, float offY, float offZ, Direction face, Box box, UV onFace, UV texUV, UV size) {
		UV onTexture = onTexture(texUV, onFace, box, face);
		if (!present(skin, onTexture)) {
			return;
		}
		Voxel voxel = toVoxel(onFace, box, face);
		boolean solidPixel = solid(skin, onTexture);

		Set<Direction> hide = new HashSet<>();
		List<Direction[]> corners = new ArrayList<>();
		boolean onBorder = false;
		boolean backsideOverlaps = false;

		for (Direction neighbour : Direction.values()) {
			if (neighbour.getAxis() == face.getAxis()) {
				continue;
			}
			Voxel next = new Voxel(voxel.x + neighbour.getStepX(), voxel.y + neighbour.getStepY(),
					voxel.z + neighbour.getStepZ());
			UV nextUV = toFaceUV(next, box, face);
			if (inside(nextUV, size)) {
				if (present(skin, onTexture(texUV, nextUV, box, face))) {
					// a translucent neighbour never hides a solid pixel's face
					if (!(solidPixel && !solid(skin, onTexture(texUV, nextUV, box, face)))) {
						hide.add(neighbour);
					}
				} else {
					// the row may continue around the box edge onto the next face
					Voxel far = new Voxel(next.x + neighbour.getStepX(), next.y + neighbour.getStepY(),
							next.z + neighbour.getStepZ());
					if (!inside(toFaceUV(far, box, face), size)) {
						UV farUV = toFaceUV(far, box, neighbour);
						if (present(skin, onTexture(texUV, farUV, box, neighbour))
								&& !(solidPixel && !solid(skin, onTexture(texUV, farUV, box, neighbour)))) {
							hide.add(neighbour);
						}
					}
				}
			} else {
				onBorder = true;
				UV wrapUV = toFaceUV(voxel, box, neighbour);
				if (present(skin, onTexture(texUV, wrapUV, box, neighbour))) {
					backsideOverlaps = true;
					hide.add(neighbour);
					corners.add(new Direction[] { face.getOpposite(), neighbour });
				} else {
					UV downUV = toFaceUV(new Voxel(voxel.x - face.getStepX(), voxel.y - face.getStepY(),
							voxel.z - face.getStepZ()), box, neighbour);
					if (present(skin, onTexture(texUV, downUV, box, neighbour))) {
						backsideOverlaps = true;
					}
				}
			}
		}

		if (!onBorder || backsideOverlaps) {
			hide.add(face.getOpposite());
		}

		cubes.add(new VoxelMesh.PixelCube(onTexture.u, onTexture.v,
				offX + voxel.x, offY + voxel.y, offZ + voxel.z, 1.0f,
				skin.getWidth(), skin.getHeight(),
				hide.toArray(new Direction[0]), corners.toArray(new Direction[0][])));
	}

	private static boolean inside(UV uv, UV size) {
		return uv.u >= 0 && uv.u < size.u && uv.v >= 0 && uv.v < size.v;
	}
}
