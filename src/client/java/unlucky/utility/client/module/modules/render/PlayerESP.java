package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Render3D;

/**
 * Player ESP with stacking layers, CS-style customization:
 * shader silhouette (glow border + optional 3D fill), 2D screen boxes with
 * HP/armor bars, skeleton, tracers, names, distance.
 */
public class PlayerESP extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Max target distance", 128, 16, 256, 8));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Include yourself (visible in Freecam)", false));
	// shader layer
	public final BooleanSetting shader = add(new BooleanSetting("Shader", "Silhouette border through walls", true));
	public final ColorSetting shaderColor = add(new ColorSetting("Shader color", "Silhouette border color", 0xFF87B93D));
	public final BooleanSetting shaderFill = add(new BooleanSetting("Shader fill", "Translucent 3D box fill", false));
	public final ColorSetting shaderFillColor = add(new ColorSetting("Fill color", "3D fill color (alpha matters)", 0x3587B93D));
	// 2D box layer
	public final BooleanSetting box2d = add(new BooleanSetting("Box 2D", "CS-style screen-space box", true));
	public final ModeSetting boxStyle = add(new ModeSetting("Box style", "Full frame or corners", "Corners", "Full", "Corners"));
	public final ColorSetting boxColor = add(new ColorSetting("Box color", "2D box color", 0xFFF2F2F2));
	public final BooleanSetting boxShadow = add(new BooleanSetting("Box shadow", "Black backing line for crispness", true));
	public final BooleanSetting healthBar = add(new BooleanSetting("Health bar", "HP bar left of the box", true));
	public final BooleanSetting armorBar = add(new BooleanSetting("Armor bar", "Armor bar right of the box", true));
	public final BooleanSetting names = add(new BooleanSetting("Names", "Player name above the box", true));
	public final BooleanSetting distance = add(new BooleanSetting("Distance", "Distance below the box", false));
	// skeleton layer
	public final BooleanSetting skeleton = add(new BooleanSetting("Skeleton", "Bone lines on the model", false));
	public final ColorSetting skeletonColor = add(new ColorSetting("Skeleton color", "Bone line color", 0xFF6FD9FF));
	// tracers
	public final BooleanSetting tracers = add(new BooleanSetting("Tracers", "Line from screen bottom to target", false));
	public final ColorSetting tracerColor = add(new ColorSetting("Tracer color", "Tracer line color", 0xFF87B93D));

	public PlayerESP() {
		super("PlayerESP", "Highlights other players", Category.RENDER);
	}

	private List<AbstractClientPlayer> targets() {
		List<AbstractClientPlayer> result = new ArrayList<>();
		if (mc().level == null || mc().player == null) {
			return result;
		}
		for (AbstractClientPlayer player : mc().level.players()) {
			if (player == mc().player) {
				// own body is only visible when the camera is detached (freecam / third person)
				if (self.get() && mc().gameRenderer.mainCamera().isDetached()) {
					result.add(player);
				}
			} else if (player.distanceTo(mc().player) <= range.get()) {
				result.add(player);
			}
		}
		return result;
	}

	@Override
	public void onTick() {
		// glow border is handled by the mixins; only the 3D fill is drawn here
		if (!shader.get() || !shaderFill.get()) {
			return;
		}
		for (AbstractClientPlayer player : targets()) {
			Render3D.box(player.getBoundingBox().inflate(0.05), 0, 0, shaderFillColor.get(), true);
		}
	}

	/** Called from the HUD layer every frame. */
	public void renderOverlay(GuiGraphicsExtractor g, float partialTick) {
		if (!box2d.get() && !skeleton.get() && !tracers.get()) {
			return;
		}
		int guiWidth = g.guiWidth();
		int guiHeight = g.guiHeight();

		for (AbstractClientPlayer player : targets()) {
			Vec3 base = player.getPosition(partialTick);
			float halfWidth = player.getBbWidth() / 2.0f + 0.1f;
			float height = player.getBbHeight() + 0.1f;

			// project the eight corners of the (interpolated) bounding box
			double minX = Double.MAX_VALUE;
			double minY = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double maxY = -Double.MAX_VALUE;
			boolean visible = true;
			for (int i = 0; i < 8 && visible; i++) {
				double cx = base.x + ((i & 1) == 0 ? -halfWidth : halfWidth);
				double cy = base.y + ((i & 2) == 0 ? 0 : height);
				double cz = base.z + ((i & 4) == 0 ? -halfWidth : halfWidth);
				Vec3 screen = Render3D.worldToScreen(new Vec3(cx, cy, cz), guiWidth, guiHeight);
				if (screen == null) {
					visible = false;
				} else {
					minX = Math.min(minX, screen.x);
					minY = Math.min(minY, screen.y);
					maxX = Math.max(maxX, screen.x);
					maxY = Math.max(maxY, screen.y);
				}
			}
			if (!visible || maxX < 0 || maxY < 0 || minX > guiWidth || minY > guiHeight) {
				continue;
			}

			int x = (int) Math.round(minX);
			int y = (int) Math.round(minY);
			int w = Math.max((int) Math.round(maxX - minX), 2);
			int h = Math.max((int) Math.round(maxY - minY), 2);

			if (tracers.get()) {
				Render2D.line(g, guiWidth / 2.0f, guiHeight, x + w / 2.0f, y + h, 1, tracerColor.get());
			}
			if (skeleton.get()) {
				drawSkeleton(g, player, base, partialTick, guiWidth, guiHeight);
			}
			if (box2d.get()) {
				drawBox(g, x, y, w, h);
			}
			if (healthBar.get()) {
				float fraction = Mth.clamp(player.getHealth() / player.getMaxHealth(), 0.0f, 1.0f);
				int barHeight = (int) (h * fraction);
				Render2D.rect(g, x - 4, y - 1, 2, h + 2, 0xC0101010);
				Render2D.rect(g, x - 4, y + h + 1 - barHeight, 2, barHeight,
						ColorUtil.lerp(0xFFFF4040, 0xFF40FF60, fraction));
			}
			if (armorBar.get()) {
				float fraction = Mth.clamp(player.getArmorValue() / 20.0f, 0.0f, 1.0f);
				if (fraction > 0) {
					int barHeight = (int) (h * fraction);
					Render2D.rect(g, x + w + 2, y - 1, 2, h + 2, 0xC0101010);
					Render2D.rect(g, x + w + 2, y + h + 1 - barHeight, 2, barHeight, 0xFF6F9FFF);
				}
			}
			if (names.get()) {
				String name = player.getName().getString();
				Render2D.text(g, name, x + w / 2 - Render2D.width(name) / 2, y - 11, 0xFFF2F2F2);
			}
			if (distance.get()) {
				String text = (int) player.distanceTo(mc().player) + "m";
				Render2D.text(g, text, x + w / 2 - Render2D.width(text) / 2, y + h + 3, 0xFFB8B8C0);
			}
		}
	}

	private void drawBox(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int color = boxColor.get();
		if (boxStyle.is("Full")) {
			if (boxShadow.get()) {
				g.outline(x - 1, y - 1, w + 2, h + 2, 0xC0000000);
				g.outline(x + 1, y + 1, w - 2, h - 2, 0xC0000000);
			}
			g.outline(x, y, w, h, color);
		} else {
			int len = Math.max(Math.min(w, h) / 4, 3);
			if (boxShadow.get()) {
				drawCorners(g, x - 1, y - 1, w + 2, h + 2, len, 3, 0xC0000000);
			}
			drawCorners(g, x, y, w, h, len, 1, color);
		}
	}

	private static void drawCorners(GuiGraphicsExtractor g, int x, int y, int w, int h, int len, int thickness, int color) {
		// top left
		Render2D.rect(g, x, y, len, thickness, color);
		Render2D.rect(g, x, y, thickness, len, color);
		// top right
		Render2D.rect(g, x + w - len, y, len, thickness, color);
		Render2D.rect(g, x + w - thickness, y, thickness, len, color);
		// bottom left
		Render2D.rect(g, x, y + h - thickness, len, thickness, color);
		Render2D.rect(g, x, y + h - len, thickness, len, color);
		// bottom right
		Render2D.rect(g, x + w - len, y + h - thickness, len, thickness, color);
		Render2D.rect(g, x + w - thickness, y + h - len, thickness, len, color);
	}

	private void drawSkeleton(GuiGraphicsExtractor g, Player player, Vec3 base, float partialTick, int guiWidth, int guiHeight) {
		float bodyYaw = (float) Math.toRadians(Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot));
		Vec3 forward = new Vec3(-Math.sin(bodyYaw), 0, Math.cos(bodyYaw));
		Vec3 right = new Vec3(Math.cos(bodyYaw), 0, Math.sin(bodyYaw));

		float h = player.getBbHeight();
		double swing = Math.cos(player.walkAnimation.position(partialTick) * 0.6662)
				* 0.9 * player.walkAnimation.speed(partialTick);

		Vec3 hipCenter = base.add(0, h * 0.45, 0);
		Vec3 neck = base.add(0, h * 0.78, 0);
		Vec3 headTop = base.add(0, h * 0.94, 0);
		Vec3 shoulderL = neck.subtract(right.scale(h * 0.17));
		Vec3 shoulderR = neck.add(right.scale(h * 0.17));
		Vec3 handL = shoulderL.add(forward.scale(Math.sin(-swing) * h * 0.2)).add(0, -h * 0.3, 0);
		Vec3 handR = shoulderR.add(forward.scale(Math.sin(swing) * h * 0.2)).add(0, -h * 0.3, 0);
		Vec3 hipL = hipCenter.subtract(right.scale(h * 0.09));
		Vec3 hipR = hipCenter.add(right.scale(h * 0.09));
		Vec3 footL = base.subtract(right.scale(h * 0.09)).add(forward.scale(Math.sin(swing) * h * 0.22));
		Vec3 footR = base.add(right.scale(h * 0.09)).add(forward.scale(Math.sin(-swing) * h * 0.22));

		int color = skeletonColor.get();
		boneLine(g, headTop, neck, color, guiWidth, guiHeight);
		boneLine(g, neck, hipCenter, color, guiWidth, guiHeight);
		boneLine(g, shoulderL, shoulderR, color, guiWidth, guiHeight);
		boneLine(g, shoulderL, handL, color, guiWidth, guiHeight);
		boneLine(g, shoulderR, handR, color, guiWidth, guiHeight);
		boneLine(g, hipL, hipR, color, guiWidth, guiHeight);
		boneLine(g, hipL, footL, color, guiWidth, guiHeight);
		boneLine(g, hipR, footR, color, guiWidth, guiHeight);
	}

	private static void boneLine(GuiGraphicsExtractor g, Vec3 from, Vec3 to, int color, int guiWidth, int guiHeight) {
		Vec3 a = Render3D.worldToScreen(from, guiWidth, guiHeight);
		Vec3 b = Render3D.worldToScreen(to, guiWidth, guiHeight);
		if (a != null && b != null) {
			Render2D.line(g, (float) a.x, (float) a.y, (float) b.x, (float) b.y, 1, color);
		}
	}
}
