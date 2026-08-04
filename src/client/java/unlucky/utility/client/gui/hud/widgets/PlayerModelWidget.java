package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Your own live model in a corner — head and pitch follow where you look. */
public class PlayerModelWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("PlayerModel", "Your own live model in a corner", false));
	public final BooleanSetting bg = add(new BooleanSetting("PlayerModel bg", "Backing behind the player model", true));
	public final ModeSetting background = add(new ModeSetting("Model background", "Panel, themed gradient, or transparent", "Panel", "Panel", "Gradient", "None"));
	public final ModeSetting rotation = add(new ModeSetting("Model rotation", "Follow your view, stay static, or rotate continuously", "Follow", "Follow", "Static", "Spin"));
	public final BooleanSetting follow = add(new BooleanSetting("Model follows look", "Head and pitch track where you look", true));
	public final NumberSetting staticRotation = add(new NumberSetting("Model angle", "Static model body angle", 15, -180, 180, 5));
	public final BooleanSetting idle = add(new BooleanSetting("Model idle animation", "Animate subtle breathing and idle motion", true));
	public final NumberSetting idleSpeed = add(new NumberSetting("Model idle speed", "Speed of idle movement", 1.0, 0.2, 3.0, 0.1));
	public final BooleanSetting armor = add(new BooleanSetting("Model armor", "Show your armor on the model", true));
	public final BooleanSetting cape = add(new BooleanSetting("Model cape", "Show your cape on the model", true));
	public final BooleanSetting held = add(new BooleanSetting("Model held items", "Show held items on the model", true));
	public final ModeSetting lighting = add(new ModeSetting("Model lighting", "Normal, bright studio, or dim presentation", "Normal", "Normal", "Bright", "Dim"));
	public final BooleanSetting showName = add(new BooleanSetting("Model name", "Show your name below the model", false));
	public final BooleanSetting showHealth = add(new BooleanSetting("Model health", "Show current health and absorption", false));
	public final NumberSetting scale = add(new NumberSetting("Model scale", "Size of the player preview", 100, 60, 160, 5));
	public final ModeSetting crop = add(new ModeSetting("Model crop", "Frame the full body, upper body, or head", "Full", "Full", "Upper", "Head"));
	public final NumberSetting cropOffset = add(new NumberSetting("Model crop offset", "Move the cropped model vertically in its frame", 0, -20, 20, 1));

	private static final int WIDTH = 46;
	private static final int HEIGHT = 72;
	private static final Quaternionf FLIP = new Quaternionf().rotateZ((float) Math.PI);

	public PlayerModelWidget() {
		super("PlayerModel");
		background.showWhen(bg::get);
		follow.showWhen(() -> rotation.is("Follow"));
		staticRotation.showWhen(() -> rotation.is("Static"));
		idleSpeed.showWhen(idle::get);
		cropOffset.showWhen(() -> !crop.is("Full"));
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	public boolean requiresPlayer() {
		return false;
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.5);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		float factor = scale.getFloat() / 100.0f;
		int width = Math.round(WIDTH * factor);
		int labelLines = (showName.get() ? 1 : 0) + (showHealth.get() ? 1 : 0);
		int labelHeight = Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int modelHeight = Math.round(HEIGHT * factor);
		int height = modelHeight + labelLines * labelHeight;
		setSize(width, height);
		drawBackground(g, width, height);
		if (mc().player == null) {
			if (editing) {
				if (HudManager.isPreviewData()) {
					drawEditorPreview(g, width, modelHeight);
				} else {
					Render2D.text(g, "PlayerModel", getX() + (width - Render2D.width("PlayerModel")) / 2,
							getY() + modelHeight / 2 - 4, Theme.textDim);
				}
			}
			return;
		}
		float bodyAngle = staticRotation.getFloat();
		float headOffset = 0.0f;
		float pitch = 0.0f;
		if (rotation.is("Follow") && follow.get()) {
			bodyAngle = 15.0f;
			headOffset = Mth.wrapDegrees(mc().player.getYHeadRot() - mc().player.yBodyRot);
			pitch = mc().player.getXRot();
		} else if (rotation.is("Spin")) {
			bodyAngle = (System.currentTimeMillis() % 8000L) / 8000.0f * 360.0f;
		}
		if (idle.get()) {
			float wave = (float) Math.sin(System.currentTimeMillis() / 450.0 * idleSpeed.getFloat());
			headOffset += wave * 2.0f;
			pitch += wave * 1.2f;
		}
		drawModel(g, mc().player, getX() + 2, getY() + 2, getX() + width - 2, getY() + modelHeight - 2,
				bodyAngle, headOffset, pitch);
		applyLighting(g, width, modelHeight);
		drawLabels(g, width, modelHeight, mc().player.getGameProfile().name(),
				mc().player.getHealth(), mc().player.getAbsorptionAmount());
	}

	private void drawBackground(GuiGraphicsExtractor g, int width, int height) {
		if (hasExplicitBackgroundOverride() || !bg.get() || background.is("None")) return;
		if (background.is("Gradient")) {
			Render2D.roundedGradient(g, getX(), getY(), width, height, Theme.hudPanelRadius,
					ColorUtil.withAlpha(accentAt(0, width), 90), ColorUtil.withAlpha(accentAt(width, width), 90));
		} else {
			Render2D.hudPanel(g, getX(), getY(), width, height, true);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void drawModel(GuiGraphicsExtractor g, LivingEntity entity, int x0, int y0, int x1, int y1,
			float bodyAngle, float headOffset, float pitch) {
		EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
		EntityRenderState state = renderer.createRenderState(entity, 1.0f);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180.0f + bodyAngle;
			living.yRot = bodyAngle + headOffset;
			living.xRot = pitch;
			living.boundingBoxWidth /= living.scale;
			living.boundingBoxHeight /= living.scale;
			living.scale = 1.0f;
			if (!idle.get()) {
				living.ageInTicks = 0.0f;
				living.walkAnimationPos = 0.0f;
				living.walkAnimationSpeed = 0.0f;
			}
		}
		if (!armor.get() && state instanceof HumanoidRenderState humanoid) {
			humanoid.headEquipment = ItemStack.EMPTY;
			humanoid.chestEquipment = ItemStack.EMPTY;
			humanoid.legsEquipment = ItemStack.EMPTY;
			humanoid.feetEquipment = ItemStack.EMPTY;
		}
		if (!held.get() && state instanceof ArmedEntityRenderState armed) {
			armed.rightHandItemState.clear();
			armed.rightHandItemStack = ItemStack.EMPTY;
			armed.leftHandItemState.clear();
			armed.leftHandItemStack = ItemStack.EMPTY;
		}
		if (state instanceof AvatarRenderState avatar) {
			avatar.showCape = cape.get();
		}
		float h = Math.max(state.boundingBoxHeight, 0.25f);
		float w = Math.max(Math.max(state.boundingBoxWidth, 0.25f), h * 0.55f);
		float zoom = crop.is("Head") ? 2.55f : crop.is("Upper") ? 1.55f : 1.0f;
		float renderScale = 0.82f * Math.min((y1 - y0) / h, (x1 - x0) / w) * zoom;
		float cropLift = crop.is("Head") ? h * 0.31f : crop.is("Upper") ? h * 0.14f : 0.0f;
		cropLift += cropOffset.getFloat() / Math.max(renderScale, 0.01f);
		g.entity(state, renderScale, new Vector3f(0.0f, h / 2.0f - cropLift, 0.0f), FLIP, null, x0, y0, x1, y1);
	}

	private void applyLighting(GuiGraphicsExtractor g, int width, int modelHeight) {
		if (lighting.is("Dim")) {
			Render2D.roundedRect(g, getX() + 2, getY() + 2, width - 4, modelHeight - 4, 3, 0x26000000);
		} else if (lighting.is("Bright")) {
			Render2D.horizontalGradient(g, getX() + 3, getY() + 3, width - 6, modelHeight - 6, 0x18FFFFFF, 0x00FFFFFF);
		}
	}

	private void drawLabels(GuiGraphicsExtractor g, int width, int modelHeight, String name, float health, float absorption) {
		int y = getY() + modelHeight;
		if (showName.get()) {
			String clipped = name.length() > 16 ? name.substring(0, 16) : name;
			Render2D.text(g, clipped, getX() + (width - Render2D.width(clipped)) / 2, y, Theme.text);
			y += Math.max(9, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		}
		if (showHealth.get()) {
			String value = String.format(java.util.Locale.ROOT, "%.1f", health) + (absorption > 0 ? " +" + Math.round(absorption) : "");
			Render2D.text(g, value, getX() + (width - Render2D.width(value)) / 2, y, absorption > 0 ? 0xFFF2C94C : 0xFF55DD77);
		}
	}

	private void drawEditorPreview(GuiGraphicsExtractor g, int width, int modelHeight) {
		int cx = getX() + width / 2;
		int head = crop.is("Head") ? 18 : 12;
		Render2D.roundedGradient(g, cx - head / 2, getY() + 7, head, head, 4, accentAt(0, head), accentAt(head, head));
		if (!crop.is("Head")) {
			Render2D.roundedRect(g, cx - 7, getY() + 20, 14, Math.max(12, modelHeight - 26), 4, ColorUtil.withAlpha(accentAt(1, 1), 170));
		}
		drawLabels(g, width, modelHeight, "Player", 18.0f, 2.0f);
	}
}
