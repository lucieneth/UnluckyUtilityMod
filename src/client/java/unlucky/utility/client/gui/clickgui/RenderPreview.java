package unlucky.utility.client.gui.clickgui;

import java.util.List;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.skins.SkinRender;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.player.Capes;
import unlucky.utility.client.module.modules.render.Chams;
import unlucky.utility.client.module.modules.render.ElytraPhysics;
import unlucky.utility.client.module.modules.render.RainbowEnchant;
import unlucky.utility.client.module.modules.render.SkinLayers3D;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ChamsRenderState;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.HudEntity;
import unlucky.utility.client.util.Render2D;

/**
 * A drag-to-rotate model viewport, shared by the Capes option row and the Preview
 * tab of both ClickGUIs.
 *
 * <p><b>Live mode</b> (no cape supplier, in a world) renders your real player through
 * {@code g.entity(...)}, the same picture-in-picture path the PlayerModel HUD widget uses.
 * That goes through the real entity renderers, so every extract/submit mixin — Chams,
 * 3D skin layers, the Capes swap, enchant glint on your armour — shows up exactly as it
 * would in the world. Chams normally skips you unless "Self" is on; {@link #capturing()}
 * tells it this render is the preview, so you can see the effect while tuning it.
 *
 * <p><b>Skin mode</b> (a cape supplier, or no world to take a player from) draws your
 * account skin with {@link SkinRender}, which works on the title screen too.
 */
public final class RenderPreview {
	/** The modules whose look the Preview tab shows on the model, in the order it lists them. */
	public static final List<Class<? extends Module>> MODULES = List.of(
			Chams.class, Capes.class, SkinLayers3D.class, RainbowEnchant.class, ElytraPhysics.class);

	private static final Quaternionf FLIP = new Quaternionf().rotateZ((float) Math.PI);
	private static final String HINT = "Drag to rotate";

	private static boolean capturing;

	private final Supplier<Identifier> cape;
	private float yaw;
	private boolean dragging;
	private double lastX;
	private int x, y, width, height;

	/**
	 * @param cape       cape for skin mode, or null to preview the live player
	 * @param initialYaw starting spin; 180 shows the back, where a cape is
	 */
	public RenderPreview(Supplier<Identifier> cape, float initialYaw) {
		this.cape = cape;
		this.yaw = initialYaw;
	}

	/** True only while the preview is extracting your render state — read by Chams. */
	public static boolean capturing() {
		return capturing;
	}

	public void render(GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		boolean hover = dragging || Render2D.hovered(mouseX, mouseY, x, y, width, height);
		Render2D.rect(g, x, y, width, height, Theme.borderDark);
		Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, ColorUtil.withAlpha(Theme.surface, 200));

		int modelTop = y + 4;
		int modelBottom = y + height - 12;
		Minecraft mc = Minecraft.getInstance();
		if (cape == null && mc.player != null && mc.level != null) {
			drawLive(g, mc.player, x + 2, modelTop, x + width - 2, modelBottom);
		} else {
			drawSkin(g, x + 2, modelTop, width - 4, modelBottom - modelTop, mouseX, mouseY);
		}
		Render2D.textNoShadow(g, HINT, x + (width - Render2D.width(HINT)) / 2, y + height - 10,
				hover ? Theme.text : Theme.textDim);
	}

	private void drawSkin(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
		PlayerSkin skin = SkinRender.ownSkin();
		Identifier capeTexture;
		if (cape != null) {
			capeTexture = cape.get();
		} else {
			// no world: still honour the Capes module so the tab isn't lying about your back
			Capes capes = UnluckyClient.INSTANCE.modules.get(Capes.class);
			PlayerSkin worn = capes.isEnabled() ? capes.apply(skin) : skin;
			capeTexture = worn.cape() == null ? null : worn.cape().texturePath();
		}
		// SkinRender adds its own 15 degree lean; take it back off so 0 faces the viewer
		SkinRender.draw(g, skin.body().texturePath(), capeTexture,
				skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM,
				false, yaw - 15.0f, x, y, w, h, mouseX, mouseY);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void drawLive(GuiGraphicsExtractor g, LocalPlayer player, int x0, int y0, int x1, int y1) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
		EntityRenderState state;
		capturing = true;
		try {
			state = renderer.createRenderState(player, 1.0f);
		} finally {
			capturing = false;
		}
		state.shadowPieces.clear();
		state.outlineColor = 0; // ESP glow is a world effect; in a framed preview it is just a box
		HudEntity.Fit fit = HudEntity.Fit.of(state);
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180.0f + yaw;
			living.yRot = 0.0f; // head yaw is relative to the body: the head turns with it, not twice as far
			living.xRot = 0.0f;
			living.scale = 1.0f;
			fit = HudEntity.frame(living, player);
			// Spinbot's ghost points at your real yaw, which has nothing to do with this spin
			((ChamsRenderState) living).unlucky$setSpinOutline(0, 0.0f);
		}
		// sized as if standing, shrunk only when the pose would not fit (see HudEntity.frame)
		float renderScale = fit.scale(x1 - x0, y1 - y0, x1 - x0, y1 - y0);
		g.entity(state, renderScale, new Vector3f(0.0f, fit.centerY(), 0.0f), FLIP, null, x0, y0, x1, y1);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != InputConstants.MOUSE_BUTTON_LEFT || !Render2D.hovered(mouseX, mouseY, x, y, width, height)) {
			return false;
		}
		dragging = true;
		lastX = mouseX;
		return true;
	}

	public void mouseDragged(double mouseX) {
		if (dragging) {
			yaw = net.minecraft.util.Mth.wrapDegrees(yaw + (float) (mouseX - lastX) * 1.6f);
			lastX = mouseX;
		}
	}

	public void mouseReleased() {
		dragging = false;
	}
}
