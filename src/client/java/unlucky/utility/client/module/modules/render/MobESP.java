package unlucky.utility.client.module.modules.render;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Shader-style mob highlighting: glow border per hostility category with an
 * optional translucent fill.
 */
public class MobESP extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Max mob distance", 64, 16, 256, 8));
	public final BooleanSetting hostile = add(new BooleanSetting("Hostile", "Monsters", true));
	public final ColorSetting hostileColor = add(new ColorSetting("Hostile color", "Monster color", 0xFFFF5555));
	public final BooleanSetting neutral = add(new BooleanSetting("Neutral", "Neutral mobs", true));
	public final ColorSetting neutralColor = add(new ColorSetting("Neutral color", "Neutral mob color", 0xFFFFC855));
	public final BooleanSetting passive = add(new BooleanSetting("Passive", "Passive mobs", false));
	public final ColorSetting passiveColor = add(new ColorSetting("Passive color", "Passive mob color", 0xFF7EE787));
	public final BooleanSetting fill = add(new BooleanSetting("Box fill", "Translucent 3D box fill (true silhouette fill needs custom shaders)", false));
	public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Alpha of the fill", 45, 10, 160, 5));

	public MobESP() {
		super("MobESP", "Highlights mobs by hostility", Category.RENDER);
	}

	/** Border color for a mob, or 0 when its category is disabled. */
	public int categoryColor(Mob mob) {
		if (mob instanceof Enemy) {
			return hostile.get() ? hostileColor.get() : 0;
		}
		if (mob instanceof NeutralMob) {
			return neutral.get() ? neutralColor.get() : 0;
		}
		return passive.get() ? passiveColor.get() : 0;
	}

	@Override
	public void onTick() {
		// glow border is handled by the mixins; only fills are drawn here
		if (!fill.get() || mc().level == null || mc().player == null) {
			return;
		}
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof Mob mob && mob.distanceTo(mc().player) <= range.get()) {
				int color = categoryColor(mob);
				if (color != 0) {
					Render3D.box(mob.getBoundingBox().inflate(0.05), 0, 0,
							ColorUtil.withAlpha(color, fillOpacity.getInt()), true);
				}
			}
		}
	}
}
