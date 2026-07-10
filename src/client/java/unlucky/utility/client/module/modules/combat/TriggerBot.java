package unlucky.utility.client.module.modules.combat;

import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatUtil;

/** Attacks automatically whenever your crosshair rests on a valid target. */
public class TriggerBot extends Module {
	// per-mob whitelists, opened by right-clicking the group toggles in the GUI
	public final unlucky.utility.client.settings.EntityListSetting hostileMobs =
			new unlucky.utility.client.settings.EntityListSetting("Hostile mobs", "Which hostile mobs to trigger on");
	public final unlucky.utility.client.settings.EntityListSetting passiveMobs =
			new unlucky.utility.client.settings.EntityListSetting("Passive mobs", "Which passive mobs to trigger on");

	public final BooleanSetting players = add(new BooleanSetting("Players", "Trigger on players", true));
	public final BooleanSetting hostiles = add(new BooleanSetting("Hostiles", "Trigger on hostile mobs — right-click to pick which", true)
			.withMobList(hostileMobs, true));
	public final BooleanSetting passives = add(new BooleanSetting("Passives", "Trigger on passive mobs — right-click to pick which", false)
			.withMobList(passiveMobs, false));
	public final ModeSetting speed = add(new ModeSetting("Speed", "Attributes = full weapon charge, CPS = flat rate", "Attributes", "Attributes", "CPS"));
	public final NumberSetting cps = add(new NumberSetting("CPS", "Clicks per second in CPS mode", 8, 1, 20, 1));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs", "Don't attack with a screen open", true));

	private int ticksSinceAttack;

	public TriggerBot() {
		super("TriggerBot", "Attacks what you aim at", Category.COMBAT);
		// config-persisted, GUI-hidden; edited through the right-click picker
		add(hostileMobs);
		add(passiveMobs);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}
		ticksSinceAttack++;

		if (!(mc().hitResult instanceof EntityHitResult hit) || mc().hitResult.getType() != HitResult.Type.ENTITY) {
			return;
		}
		if (!CombatUtil.validTarget(hit.getEntity(), players.get(), hostiles.get(), passives.get(), hostileMobs, passiveMobs)) {
			return;
		}
		if (CombatUtil.ready(speed.is("Attributes"), cps.get(), ticksSinceAttack)) {
			CombatUtil.attack(hit.getEntity());
			ticksSinceAttack = 0;
		}
	}
}
