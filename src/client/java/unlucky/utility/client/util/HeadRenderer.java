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
}
