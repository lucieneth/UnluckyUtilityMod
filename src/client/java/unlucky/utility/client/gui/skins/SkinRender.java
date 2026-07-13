package unlucky.utility.client.gui.skins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Shared 3D skin-model drawing for the skin changer: the same
 * {@code GuiGraphicsExtractor.skin(...)} primitive vanilla's
 * {@code PlayerSkinWidget} uses. The body stands still, angled slightly toward
 * the menu buttons; only the <b>head part</b> tracks the mouse — vanilla's
 * {@code GuiSkinRenderer.submitModel} renders the baked {@code ModelPart} tree
 * with whatever pose it carries (no reset), so rotating our private baked
 * head each frame is enough. The hat layer is a child of the head in 26.2
 * ({@code HumanoidModel}: {@code head.getChild("hat")}), so it follows free.
 *
 * <p>Signs, derived from {@code GuiSkinRenderer}'s matrices (not guessed):
 * {@code head.xRot}+ pitches the face down on screen (vertical axis is
 * flip-proof), and screen-right needs {@code head.yRot} negative — the
 * renderer itself negates its yaw param ({@code YP.rotationDegrees(-rotationY)})
 * for the same reason. Models are baked once and reused (player geometry is
 * fixed, so surviving a resource reload is harmless).
 */
public final class SkinRender {
	/** Body stance: a touch toward the menu column (viewer's right) + subtle tilt. */
	private static final float BODY_YAW = 15.0f;
	private static final float BODY_PITCH = -5.0f;

	private static Model.Simple wide;
	private static Model.Simple slim;
	private static ModelPart wideHead;
	private static ModelPart slimHead;

	private SkinRender() {
	}

	/** Still body, head tracking (mouseX, mouseY); {@code slimModel} picks Alex arms. */
	public static void draw(GuiGraphicsExtractor g, Identifier texture, boolean slimModel,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		if (wide == null) {
			EntityModelSet models = Minecraft.getInstance().getEntityModels();
			ModelPart wideRoot = models.bakeLayer(ModelLayers.PLAYER);
			ModelPart slimRoot = models.bakeLayer(ModelLayers.PLAYER_SLIM);
			wideHead = wideRoot.getChild("head");
			slimHead = slimRoot.getChild("head");
			wide = new Model.Simple(wideRoot, RenderTypes::entityTranslucent);
			slim = new Model.Simple(slimRoot, RenderTypes::entityTranslucent);
		}
		float scale = 0.97f * h / 2.125f;
		// eyes sit ~18% down the widget; clamp inside neck-breaking territory
		float lookYaw = Mth.clamp((mouseX - (x + w / 2.0f)) * 0.55f, -70.0f, 70.0f);
		float lookPitch = Mth.clamp((mouseY - (y + h * 0.18f)) * 0.4f, -30.0f, 50.0f);
		ModelPart head = slimModel ? slimHead : wideHead;
		head.yRot = (float) Math.toRadians(-(lookYaw - BODY_YAW));
		head.xRot = (float) Math.toRadians(lookPitch);
		g.skin(slimModel ? slim : wide, texture, scale, BODY_PITCH, BODY_YAW, -1.0625f, x, y, x + w, y + h);
	}

	/** Convenience: draw a full {@link PlayerSkin}. */
	public static void draw(GuiGraphicsExtractor g, PlayerSkin skin,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		draw(g, skin.body().texturePath(), skin.model() == PlayerModelType.SLIM, x, y, w, h, mouseX, mouseY);
	}

	/**
	 * The logged-in account's skin — via the vanilla render cache, so it
	 * resolves asynchronously and serves Steve/Alex until the download lands
	 * (offline dev sessions just keep the default).
	 */
	public static PlayerSkin ownSkin() {
		Minecraft mc = Minecraft.getInstance();
		return mc.playerSkinRenderCache()
				.getOrDefault(ResolvableProfile.createUnresolved(mc.getUser().getProfileId()))
				.playerSkin();
	}
}
