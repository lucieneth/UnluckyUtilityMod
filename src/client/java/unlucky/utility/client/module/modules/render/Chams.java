package unlucky.utility.client.module.modules.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;

/** Renders entities as solid, tinted silhouettes, optionally visible through terrain. */
public class Chams extends Module {
	public final BooleanSetting players = add(new BooleanSetting("Players", "Chams on players", true));
	public final BooleanSetting mobs = add(new BooleanSetting("Mobs", "Chams on mobs", false));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Chams on your own model (third person)", false));
	public final ColorSetting color = add(new ColorSetting("Color", "Silhouette color (visible parts in CS:GO mode)", Theme.accent1));
	public final BooleanSetting csgo = add(new BooleanSetting("CS:GO mode", "Two-tone: one color in sight, another through walls", false));
	public final ColorSetting wallColor = add(new ColorSetting("Wall color", "Through-wall color used in CS:GO mode", 0xFFFF5050));
	public final NumberSetting opacity = add(new NumberSetting("Opacity", "Silhouette opacity", 160, 20, 255, 5));
	public final NumberSetting range = add(new NumberSetting("Range", "Max distance", 64, 8, 256, 8));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls", "Show silhouettes through terrain (non-CS:GO mode)", true));

	public Chams() {
		super("Chams", "Renders entities as solid see-through silhouettes", Category.RENDER);
	}

	/** ARGB tint for this entity's chams pass, or 0 to skip it. */
	public int colorFor(Entity entity) {
		if (!isEnabled()) {
			return 0;
		}
		Minecraft mc = mc();
		if (mc.player == null) {
			return 0;
		}
		int argb = (opacity.getInt() << 24) | (color.get() & 0xFFFFFF);
		if (entity instanceof Player) {
			boolean isSelf = entity == mc.player;
			if ((isSelf && !self.get()) || (!isSelf && !players.get())) {
				return 0;
			}
			return entity.distanceTo(mc.player) <= range.getFloat() ? argb : 0;
		}
		if (entity instanceof Mob) {
			return mobs.get() && entity.distanceTo(mc.player) <= range.getFloat() ? argb : 0;
		}
		return 0;
	}

	/** Through-wall (occluded) tint for CS:GO mode. */
	public int wallArgb() {
		return (opacity.getInt() << 24) | (wallColor.get() & 0xFFFFFF);
	}
}
