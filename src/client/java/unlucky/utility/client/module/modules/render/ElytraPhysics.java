package unlucky.utility.client.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Gives the worn elytra cape-like sway instead of rendering rigid. Purely
 * visual, a port of OhHeyItsJosh's Elytra-Physics approach: the whole elytra
 * layer is rotated as one rigid unit on the PoseStack (WingsLayerMixin), the
 * wings spread via the state's actual spread axis (AvatarRendererMixin), and
 * the cape simulation that feeds the sway values gets its 10-block hard snap
 * replaced with a smooth clamp (ClientAvatarStateMixin) so nothing jerks at
 * ElytraFly speeds. Never touch the wing Euler angles directly — they are
 * mirrored parts, per-wing offsets distort asymmetrically.
 */
public class ElytraPhysics extends Module {
	public final NumberSetting intensity = add(new NumberSetting("Intensity",
			"How much the elytra sways with movement", 1.0, 0.0, 3.0, 0.1));
	public final BooleanSetting smoothCape = add(new BooleanSetting("Smooth cape sim",
			"Replace the cape simulation's 10-block snap with a smooth clamp — stops the elytra (and cape) jerking at high speed", true));

	public ElytraPhysics() {
		super("ElytraPhysics", "Cape-like sway physics for the worn elytra", Category.RENDER);
	}

	/**
	 * Rotates the whole elytra assembly around the body from the cape motion
	 * values: capeLean/capeFlap tilt it back, capeLean2 pans and rolls it
	 * sideways. Faded out by fallFlyingScale() so real elytra flight keeps its
	 * vanilla pose. Cape values are in degrees.
	 */
	public void applySway(PoseStack poseStack, AvatarRenderState state) {
		float i = intensity.getFloat();
		float lean = state.isVisuallySwimming ? 0.25f : 0.85f;
		Quaternionf sway = new Quaternionf()
				.rotateX((lean * state.capeLean / 2.0f + state.capeFlap) * Mth.DEG_TO_RAD * i)
				.rotateZ(state.capeLean2 / 2.0f * Mth.DEG_TO_RAD * i)
				.rotateY(-state.capeLean2 / 2.0f * Mth.DEG_TO_RAD * i);
		if (state.isFallFlying) {
			sway = sway.slerp(new Quaternionf(), state.fallFlyingScale());
		}
		poseStack.translate(0.0f, 0.2f * (state.capeLean / 150.0f) * i, 0.0f);
		poseStack.mulPose(sway);
	}

	/** Extra wing spread (radians) from cape motion, applied on elytraRotZ — the real spread axis. */
	public float wingSpread(AvatarRenderState state) {
		// 0.004363323 = 0.25 degrees in radians, same constant the original mod uses
		return (0.75f * state.capeLean / 2.0f + state.capeFlap) * 0.004363323f * intensity.getFloat();
	}
}
