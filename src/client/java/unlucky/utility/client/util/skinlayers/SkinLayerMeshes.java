package unlucky.utility.client.util.skinlayers;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Per-skin 3D layer meshes (3d-skin-layers recreation): six {@link VoxelMesh}es
 * built from the skin's overlay regions with their exact part table — hat
 * 8x8x8@(32,0) bottom-pivot +0.6, jacket 8x12x4@(16,32), sleeves 12-tall
 * 4(3 slim)-wide @(40,32)/(48,48) top-pivot -2, pants 4x12x4@(0,32)/(0,48).
 * Keyed by skin texture + slim flag; a failed build caches {@link #FAILED} so
 * unsupported skins (HD, not-yet-downloaded) don't rebuild every frame — a
 * skin texture change means a new Identifier, which re-keys naturally.
 *
 * <p>Pixel access: bundled default skins read via the resource manager;
 * downloaded skins pull {@code DynamicTexture#getPixels} from the texture
 * manager (26.2's SkinTextureDownloader registers them main-thread, so by the
 * time a player renders, the texture object exists). Meshes build
 * synchronously — one 64x64 scan, same as the original mod.
 */
public final class SkinLayerMeshes {
	/** Sentinel for "tried and failed" — all six meshes empty. */
	public static final PartMeshes FAILED = new PartMeshes(VoxelMesh.EMPTY, VoxelMesh.EMPTY,
			VoxelMesh.EMPTY, VoxelMesh.EMPTY, VoxelMesh.EMPTY, VoxelMesh.EMPTY);

	public record PartMeshes(VoxelMesh head, VoxelMesh torso, VoxelMesh leftArm, VoxelMesh rightArm,
			VoxelMesh leftLeg, VoxelMesh rightLeg) {
		public boolean usable() {
			return this != FAILED;
		}
	}

	private record Key(Identifier skin, boolean slim) {
	}

	private static final Map<Key, PartMeshes> CACHE = new HashMap<>();

	private SkinLayerMeshes() {
	}

	/**
	 * Meshes for a skin, building on first sight. Render thread only. A skin whose
	 * texture isn't downloaded yet returns FAILED <em>without</em> caching, so it
	 * retries next frame; only real results (usable meshes, or a permanent
	 * HD-skin fail) are cached.
	 */
	public static PartMeshes get(Identifier skinTexture, boolean slim) {
		Key key = new Key(skinTexture, slim);
		PartMeshes cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		PartMeshes built = build(key); // null = texture not ready, retry later
		if (built != null) {
			CACHE.put(key, built);
			return built;
		}
		return FAILED;
	}

	public static void clear() {
		CACHE.clear();
	}

	/** @return usable meshes, {@link #FAILED} for a permanent (HD) fail, or null if the texture isn't ready yet */
	private static PartMeshes build(Key key) {
		NativeImage skin = null;
		boolean ownedImage = false;
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(key.skin());
			if (resource.isPresent()) {
				try (InputStream in = resource.get().open()) {
					skin = NativeImage.read(in);
					ownedImage = true; // we decoded it, we close it
				}
			} else {
				AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(key.skin());
				if (texture instanceof DynamicTexture dynamic) {
					skin = dynamic.getPixels(); // owned by the texture — never close
				}
			}
			if (skin == null) {
				return null; // not downloaded yet — try again next frame
			}
			if (skin.getWidth() != 64 || skin.getHeight() != 64) {
				return FAILED; // HD or legacy skins can't voxelize, same as the original
			}
			int arm = key.slim() ? 3 : 4;
			return new PartMeshes(
					SolidPixelWrapper.wrapBox(skin, 8, 8, 8, 32, 0, false, 0.6f),
					SolidPixelWrapper.wrapBox(skin, 8, 12, 4, 16, 32, true, 0.0f),
					SolidPixelWrapper.wrapBox(skin, arm, 12, 4, 48, 48, true, -2.0f),
					SolidPixelWrapper.wrapBox(skin, arm, 12, 4, 40, 32, true, -2.0f),
					SolidPixelWrapper.wrapBox(skin, 4, 12, 4, 0, 48, true, 0.0f),
					SolidPixelWrapper.wrapBox(skin, 4, 12, 4, 0, 32, true, 0.0f));
		} catch (Exception ex) {
			UnluckyClientMod.LOGGER.error("Failed to build 3D skin layers for {}", key.skin(), ex);
			return FAILED;
		} finally {
			if (ownedImage && skin != null) {
				skin.close();
			}
		}
	}
}
