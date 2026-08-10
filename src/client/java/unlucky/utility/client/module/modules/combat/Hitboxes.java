package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.TargetingUtil;

/** Expands only the AABBs tested by vanilla's crosshair entity pick. */
public class Hitboxes extends Module {
	public final BooleanSetting players = add(new BooleanSetting("Players", "Expand player targets", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostile mobs", "Expand hostile mobs", false));
	public final BooleanSetting passives = add(new BooleanSetting("Passive mobs", "Expand passive mobs", false));
	public final NumberSetting horizontal = add(new NumberSetting("Horizontal expand",
			"Extra selection space on the X and Z axes", 0.25, 0.0, 1.0, 0.05));
	public final NumberSetting vertical = add(new NumberSetting("Vertical expand",
			"Extra selection space above and below", 0.10, 0.0, 1.0, 0.05));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never expand a friend's selection box", true));
	public final BooleanSetting onlyWeapon = add(new BooleanSetting("Only while holding weapon",
			"Expand only with a sword, axe or mace", false));
	public final BooleanSetting renderBoxes = add(new BooleanSetting("Render expanded box",
			"Draw every box currently eligible for expansion", false));

	public Hitboxes() {
		super("Hitboxes", "Widens attack selection without changing world collision",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public boolean activeForPick() {
		return isEnabled() && mc().player != null
				&& (!onlyWeapon.get() || CombatItemUtil.isMeleeWeapon(mc().player.getMainHandItem()));
	}

	public AABB expand(Entity entity, AABB vanilla) {
		if (!activeForPick() || !matches(entity)) return vanilla;
		return vanilla.inflate(horizontal.get(), vertical.get(), horizontal.get());
	}

	private boolean matches(Entity entity) {
		if (entity == null || entity == mc().player
				|| !TargetingUtil.matchesGroup(entity, players.get(), hostiles.get(), passives.get(), null, null)) {
			return false;
		}
		return !ignoreFriends.get() || !(entity instanceof Player)
				|| !FriendManager.isFriend(entity.getUUID());
	}

	@Override
	public void onTick() {
		if (!renderBoxes.get() || !activeForPick() || mc().level == null) return;
		int outline = 0xC0FF5050;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (matches(entity)) {
				Render3D.box(expand(entity, entity.getBoundingBox()), outline, 1.0f,
						ColorUtil.withAlpha(outline, 24), true);
			}
		}
	}
}
