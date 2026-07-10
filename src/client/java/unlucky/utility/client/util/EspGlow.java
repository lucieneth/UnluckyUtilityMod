package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.MobESP;
import unlucky.utility.client.module.modules.render.PlayerESP;
import unlucky.utility.client.module.modules.world.VanityESP;

/**
 * Decides which entities get the ESP glow outline and in what color.
 * Consulted by the glow mixins every frame. Returns 0 for "no glow".
 */
public final class EspGlow {
	private EspGlow() {
	}

	public static int colorFor(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		if (entity instanceof Player) {
			PlayerESP esp = UnluckyClient.INSTANCE.modules.get(PlayerESP.class);
			boolean isSelf = entity == mc.player;
			if (esp.isEnabled() && esp.shader.get() && (!isSelf || esp.self.get())
					&& entity.distanceTo(mc.player) <= esp.range.get()) {
				return esp.shaderColor.get() | 0xFF000000;
			}
		} else if (entity instanceof Mob mob) {
			MobESP esp = UnluckyClient.INSTANCE.modules.get(MobESP.class);
			if (esp.isEnabled() && entity.distanceTo(mc.player) <= esp.range.get()) {
				int color = esp.categoryColor(mob);
				if (color != 0) {
					return color | 0xFF000000;
				}
			}
		} else if (entity instanceof ItemFrame frame) {
			int color = UnluckyClient.INSTANCE.modules.get(VanityESP.class).frameColor(frame);
			if (color != 0) {
				return color | 0xFF000000;
			}
		}
		return 0;
	}
}
