package unlucky.utility.client.module.modules.render;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.skinlayers.SkinLayerMeshes;

/**
 * 3D skin layers (tr7zw/3d-skin-layers recreation): the flat second skin
 * layer becomes real voxel geometry — every opaque overlay pixel a little
 * cube, built per skin by {@code SolidPixelWrapper}, cached in
 * {@code SkinLayerMeshes}, and drawn by {@code SkinLayer3DFeature} (added to
 * the avatar renderer, with {@code PlayerModelMixin} hiding the flat overlay).
 *
 * <p>Third-person / other players only for now — first-person hands ride a
 * separate renderer and are a follow-up. Beyond the render distance the
 * vanilla flat layer shows instead (their LOD trick), so crowds stay cheap.
 */
public class SkinLayers3D extends Module {
	public final BooleanSetting head = add(new BooleanSetting("Head", "3D hat layer", true));
	public final BooleanSetting body = add(new BooleanSetting("Body", "3D jacket layer", true));
	public final BooleanSetting arms = add(new BooleanSetting("Arms", "3D sleeve layers", true));
	public final BooleanSetting legs = add(new BooleanSetting("Legs", "3D pants layers", true));
	public final NumberSetting renderDistance = add(new NumberSetting("Render distance",
			"Beyond this the vanilla flat layer renders instead (their LOD trick)", 24, 8, 64, 4));

	public SkinLayers3D() {
		super("3DSkinLayers", "Voxel second skin layer instead of the flat one", Category.RENDER);
	}

	/** Whether this avatar should get 3D layers this frame — the shared gate for the mixin + layer. */
	public boolean replaces(AvatarRenderState state) {
		if (!isEnabled() || state.isInvisible || state.skin == null) {
			return false;
		}
		double lod = renderDistance.get();
		return state.distanceToCameraSq <= lod * lod;
	}

	public boolean isSlim(AvatarRenderState state) {
		return state.skin.model() == PlayerModelType.SLIM;
	}

	/** Cached meshes for this avatar's skin (never null; FAILED sentinel until buildable). */
	public SkinLayerMeshes.PartMeshes meshesFor(AvatarRenderState state) {
		if (state.skin == null) {
			return SkinLayerMeshes.FAILED;
		}
		return SkinLayerMeshes.get(state.skin.body().texturePath(), isSlim(state));
	}

	@Override
	protected void onDisable() {
		SkinLayerMeshes.clear(); // rebuilt on demand; frees the mesh memory
	}
}
