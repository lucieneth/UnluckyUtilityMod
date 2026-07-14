package unlucky.utility.client.util.skinlayers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.SkinLayers3D;
import unlucky.utility.client.util.skinlayers.SkinLayerMeshes.PartMeshes;

/**
 * The avatar render layer that draws the voxel 3D skin layers (3d-skin-layers
 * recreation). Added to {@code AvatarRenderer} in its constructor via
 * {@code AvatarRendererMixin}; the flat vanilla overlay parts are hidden by
 * {@code PlayerModelMixin} so this replaces them rather than doubling up.
 *
 * <p>Each part is posed by its <em>animated base part</em>'s transform
 * ({@code ModelPart.translateAndRotate}) — the meshes carry the pivot offset
 * baked in, so they follow the walk/swing animation for free — then the
 * per-part {@link Offset} applies the mod's exact voxel scaling and nudge. The
 * geometry is streamed through {@code submitCustomGeometry} (the 26.2 deferred
 * path), which snapshots the posed matrix and calls {@link VoxelMesh#writeTo}
 * during the actual render pass.
 */
public class SkinLayer3DFeature extends RenderLayer<AvatarRenderState, PlayerModel> {
	public SkinLayer3DFeature(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
			AvatarRenderState state, float yRot, float xRot) {
		SkinLayers3D module = UnluckyClient.INSTANCE.modules.get(SkinLayers3D.class);
		if (!module.replaces(state)) {
			return;
		}
		PartMeshes meshes = module.meshesFor(state);
		if (!meshes.usable()) {
			return;
		}
		boolean slim = module.isSlim(state);
		RenderType renderType = RenderTypes.entityTranslucent(state.skin.body().texturePath(), true);
		PlayerModel model = getParentModel();
		int overlay = OverlayTexture.NO_OVERLAY;

		if (module.head.get() && state.showHat) {
			part(poseStack, collector, renderType, light, overlay, model.head, meshes.head(), Offset.HEAD, slim);
		}
		if (module.body.get() && state.showJacket) {
			part(poseStack, collector, renderType, light, overlay, model.body, meshes.torso(), Offset.BODY, slim);
		}
		if (module.arms.get()) {
			if (state.showLeftSleeve) {
				part(poseStack, collector, renderType, light, overlay, model.leftArm, meshes.leftArm(), Offset.LEFT_ARM, slim);
			}
			if (state.showRightSleeve) {
				part(poseStack, collector, renderType, light, overlay, model.rightArm, meshes.rightArm(), Offset.RIGHT_ARM, slim);
			}
		}
		if (module.legs.get()) {
			if (state.showLeftPants) {
				part(poseStack, collector, renderType, light, overlay, model.leftLeg, meshes.leftLeg(), Offset.LEG, slim);
			}
			if (state.showRightPants) {
				part(poseStack, collector, renderType, light, overlay, model.rightLeg, meshes.rightLeg(), Offset.LEG, slim);
			}
		}
	}

	private void part(PoseStack poseStack, SubmitNodeCollector collector, RenderType renderType, int light,
			int overlay, ModelPart base, VoxelMesh mesh, Offset offset, boolean slim) {
		if (mesh.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		base.translateAndRotate(poseStack); // follow the animated part
		offset.apply(poseStack, slim);
		// submitCustomGeometry snapshots the current pose; writeTo runs later in the pass
		collector.submitCustomGeometry(poseStack, renderType,
				(pose, consumer) -> mesh.writeTo(pose, consumer, light, overlay, 0xFFFFFFFF));
		poseStack.popPose();
	}

	/**
	 * The mod's exact per-part voxel scaling + pivot nudge (their OffsetProvider
	 * with default config: base 1.15, body width 1.05, head 1.18, height 1.035;
	 * Shape y-offsets body/leg -0.2, arm -0.1; arm side shift ±0.998 wide / ±0.499
	 * slim). Position nudges are in model units, divided by 16 like a ModelPart.
	 */
	private enum Offset {
		HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEG;

		void apply(PoseStack s, boolean slim) {
			switch (this) {
				case HEAD -> {
					s.translate(0.0f, -0.25f, 0.0f);
					s.scale(1.18f, 1.18f, 1.18f);
					s.translate(0.0f, 0.25f, 0.0f);
					s.translate(0.0f, -0.04f, 0.0f);
				}
				case BODY -> {
					s.scale(1.05f, 1.035f, 1.15f);
					s.translate(0.0f, -0.2f / 16.0f, 0.0f);
				}
				case LEFT_ARM -> {
					s.scale(1.15f, 1.035f, 1.15f);
					s.translate((slim ? 0.499f : 0.998f) / 16.0f, -0.1f / 16.0f, 0.0f);
				}
				case RIGHT_ARM -> {
					s.scale(1.15f, 1.035f, 1.15f);
					s.translate((slim ? -0.499f : -0.998f) / 16.0f, -0.1f / 16.0f, 0.0f);
				}
				case LEG -> {
					s.scale(1.15f, 1.035f, 1.15f);
					s.translate(0.0f, -0.2f / 16.0f, 0.0f);
				}
			}
		}
	}
}
