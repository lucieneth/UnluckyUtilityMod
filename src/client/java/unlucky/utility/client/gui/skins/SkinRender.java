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
 * Shared 3D skin-model drawing for the title-screen previews: the same
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
 * for the same reason.
 *
 * <p><b>Mirrored is a real second stance, not a sign flip at the call site.</b>
 * The two previews sit either side of the menu column, so they should lean
 * <em>toward</em> it rather than both the same way — which means the body yaw
 * and the head-tracking origin that is measured against it both negate together.
 *
 * <p><b>Each stance owns its own baked model.</b> {@code submitModel} draws the
 * pose the tree carries when the frame renders, not when this method runs, so
 * two widgets sharing one baked head would both render whichever rotation was
 * written last — the second panel would silently wear the first one's head
 * angle. Four rigs (wide/slim × normal/mirrored) is the cheap way to make that
 * impossible. They are baked once and reused; player geometry is fixed, so
 * surviving a resource reload is harmless.
 *
 * <p><b>The cape is a second pass, because one {@code skin(...)} call means one
 * texture.</b> The cape lives in its own PNG, so it cannot ride along with the
 * body however the geometry is arranged. Vanilla's {@code PLAYER_CAPE} layer is
 * the whole player hierarchy with {@code clearRecursively()} applied and one
 * cape cube added under {@code body}, so drawing that root renders the cape and
 * nothing else, at exactly the offset the body would have put it.
 *
 * <p><b>Which pass goes first depends on which way the model is facing.</b> The
 * two passes are flat composites with no depth between them, so the order <em>is</em>
 * the occlusion: a cape hangs behind the body when you are looking at the front
 * and in front of it when you are looking at the back. Fixed ordering looks
 * right until the first drag turns the model round, which is exactly when
 * somebody is looking at the cape on purpose.
 */
public final class SkinRender {
	/** Body stance: a touch toward the menu column (viewer's right) + subtle tilt. */
	private static final float BODY_YAW = 15.0f;
	private static final float BODY_PITCH = -5.0f;

	/** One baked model and the head part inside it that we pose each frame. */
	private record Rig(Model.Simple model, ModelPart head, Model.Simple cape) {
	}

	/** Indexed by {@code (slim ? 1 : 0) + (mirrored ? 2 : 0)}. */
	private static Rig[] rigs;

	private SkinRender() {
	}

	/** Still body, head tracking (mouseX, mouseY); {@code slimModel} picks Alex arms. */
	public static void draw(GuiGraphicsExtractor g, Identifier texture, boolean slimModel,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		draw(g, texture, null, slimModel, false, 0.0f, x, y, w, h, mouseX, mouseY);
	}

	/**
	 * The full draw.
	 *
	 * @param cape     cape texture, or null for a player without one
	 * @param mirrored flips the stance for the preview on the other side of the menu column
	 * @param dragYaw  extra spin from the user dragging the panel
	 */
	public static void draw(GuiGraphicsExtractor g, Identifier texture, Identifier cape,
			boolean slimModel, boolean mirrored, float dragYaw,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		if (rigs == null) {
			bake();
		}
		float bodyYaw = Mth.wrapDegrees((mirrored ? -BODY_YAW : BODY_YAW) + dragYaw);
		float scale = 0.97f * h / 2.125f;
		// eyes sit ~18% down the widget; clamp inside neck-breaking territory
		float lookYaw = Mth.clamp((mouseX - (x + w / 2.0f)) * 0.55f, -70.0f, 70.0f);
		float lookPitch = Mth.clamp((mouseY - (y + h * 0.18f)) * 0.4f, -30.0f, 50.0f);

		Rig rig = rigs[(slimModel ? 1 : 0) + (mirrored ? 2 : 0)];
		// Measured against this stance's own body yaw, so the head still points at the cursor
		// rather than at where the cursor would be if the body faced the other way.
		rig.head().yRot = (float) Math.toRadians(-(lookYaw - bodyYaw));
		rig.head().xRot = (float) Math.toRadians(lookPitch);

		// Beyond a quarter turn either way the back is toward us and the cape is the nearer of
		// the two; inside it the body is, and the cape belongs underneath.
		boolean capeInFront = Math.abs(bodyYaw) > 90.0f;
		if (cape != null && !capeInFront) {
			g.skin(rig.cape(), cape, scale, BODY_PITCH, bodyYaw, -1.0625f, x, y, x + w, y + h);
		}
		g.skin(rig.model(), texture, scale, BODY_PITCH, bodyYaw, -1.0625f, x, y, x + w, y + h);
		if (cape != null && capeInFront) {
			g.skin(rig.cape(), cape, scale, BODY_PITCH, bodyYaw, -1.0625f, x, y, x + w, y + h);
		}
	}

	/**
	 * One cape rig per stance for the same reason the heads are split: the cape's baked tree is
	 * posed nowhere here today, but sharing one across two panels is the same trap waiting for
	 * whoever adds a sway.
	 */
	private static void bake() {
		EntityModelSet models = Minecraft.getInstance().getEntityModels();
		rigs = new Rig[4];
		for (int index = 0; index < 4; index++) {
			ModelPart root = models.bakeLayer((index & 1) == 1 ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
			ModelPart capeRoot = models.bakeLayer(ModelLayers.PLAYER_CAPE);
			rigs[index] = new Rig(new Model.Simple(root, RenderTypes::entityTranslucent),
					root.getChild("head"),
					new Model.Simple(capeRoot, RenderTypes::entityTranslucent));
		}
	}

	/** Convenience: draw a full {@link PlayerSkin}. */
	public static void draw(GuiGraphicsExtractor g, PlayerSkin skin,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		draw(g, skin, false, 0.0f, x, y, w, h, mouseX, mouseY);
	}

	/**
	 * Convenience: draw a full {@link PlayerSkin}, cape included.
	 *
	 * <p>{@code cape()} is null for the overwhelming majority of accounts, which is why the cape
	 * pass is conditional rather than drawn transparent — a whole extra picture-in-picture render
	 * per frame for nothing is not a cost worth paying to save a branch.
	 */
	public static void draw(GuiGraphicsExtractor g, PlayerSkin skin, boolean mirrored, float dragYaw,
			int x, int y, int w, int h, int mouseX, int mouseY) {
		Identifier cape = skin.cape() == null ? null : skin.cape().texturePath();
		draw(g, skin.body().texturePath(), cape, skin.model() == PlayerModelType.SLIM, mirrored,
				dragYaw, x, y, w, h, mouseX, mouseY);
	}

	/**
	 * The logged-in account's skin — via the vanilla render cache, so it
	 * resolves asynchronously and serves Steve/Alex until the download lands
	 * (offline dev sessions just keep the default).
	 */
	public static PlayerSkin ownSkin() {
		Minecraft mc = Minecraft.getInstance();
		return skinOf(ResolvableProfile.createUnresolved(mc.getUser().getProfileId()));
	}

	/**
	 * Any profile's skin through the same cache — model type included, which is
	 * what lets a preview pick Alex arms for a slim skin instead of guessing.
	 */
	public static PlayerSkin skinOf(ResolvableProfile profile) {
		return Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile).playerSkin();
	}
}
