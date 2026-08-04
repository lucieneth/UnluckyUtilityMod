package unlucky.utility.client.util;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Draws a player's 2D face (the 8x8 head + hat overlay) anywhere in a GUI,
 * given only a UUID. Two-tier skin lookup:
 *
 * <ul>
 *   <li><b>Tablist fast path</b> — players on the current server already have a
 *       loaded {@link PlayerInfo} skin (and broadcast their hat-layer toggle).</li>
 *   <li><b>Vanilla skin cache</b> — anyone else goes through
 *       {@code Minecraft.playerSkinRenderCache()}, which resolves the profile and
 *       downloads the skin asynchronously; until then it serves the correct
 *       Steve/Alex default, so this never blocks the render thread.</li>
 * </ul>
 */
public final class HeadRenderer {
	private HeadRenderer() {
	}

	/** Face + hat at full opacity. */
	public static void draw(GuiGraphicsExtractor g, UUID id, int x, int y, int size) {
		draw(g, id, x, y, size, -1);
	}

	/** {@code color} is an ARGB tint (white with alpha for plain fading). */
	public static void draw(GuiGraphicsExtractor g, UUID id, int x, int y, int size, int color) {
		Minecraft mc = Minecraft.getInstance();
		Identifier texture = null;
		boolean hat = true;
		if (mc.getConnection() != null) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(id);
			if (info != null) {
				texture = info.getSkin().body().texturePath();
				hat = info.showHat();
			}
		}
		if (texture == null) {
			texture = mc.playerSkinRenderCache()
					.getOrDefault(ResolvableProfile.createUnresolved(id))
					.playerSkin().body().texturePath();
		}
		PlayerFaceExtractor.extractRenderState(g, texture, x, y, size, hat, false, color);
	}

	/**
	 * The friend mark as a corner badge on a head drawn at {@code (x, y, size)}:
	 * a 3x3 dot in the <b>top-right</b>, over a black square that leaves a 1px
	 * edge on its left and bottom so it stays readable against a light skin.
	 *
	 * <p>Every surface that draws a head uses this instead of spelling the mark
	 * out next to the name — the head already says who it is, so the badge rides
	 * along with it and the row packs tight. Top-right rather than bottom: the
	 * hair pixels there are less busy than the chin, and it lines up with the
	 * top of the text beside it.
	 *
	 * @param rgb   the mark color, alpha ignored
	 * @param alpha 0-255, so a fading chat line fades its badge with it
	 */
	public static void badge(GuiGraphicsExtractor g, int x, int y, int size, int rgb, int alpha) {
		if (rgb == 0 || alpha <= 0) {
			return;
		}
		int a = alpha << 24;
		Render2D.rect(g, x + size - 4, y, 4, 4, a);
		Render2D.rect(g, x + size - 3, y, 3, 3, a | (rgb & 0xFFFFFF));
	}
}
