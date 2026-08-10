package unlucky.utility.client.module.modules.player;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.render.Waypoints;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.waypoints.WaypointManager;

/** Queues exactly one ordinary LocalPlayer.respawn call per death. */
public class AutoRespawn extends Module {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks before respawning", 0, 0, 100, 1));
	public final BooleanSetting onlyMultiplayer = add(new BooleanSetting("Only multiplayer", "Do nothing in an integrated server", false));
	public final BooleanSetting deathToast = add(new BooleanSetting("Death message toast", "Show a local respawn notification", false));
	public final BooleanSetting deathWaypoint = add(new BooleanSetting("Create death waypoint", "Save the death position before respawning", true));
	public final BooleanSetting closeGui = add(new BooleanSetting("Close open client GUI first", "Close the death/client screen before the respawn action", true));
	private boolean queued;
	private boolean respawnSent;
	private int ticks;

	public AutoRespawn() {
		super("AutoRespawn", "Respawns through vanilla after a configurable delay", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().getConnection() == null
				|| (onlyMultiplayer.get() && mc().hasSingleplayerServer())) {
			queued = false;
			respawnSent = false;
			return;
		}
		boolean dead = mc().player.isDeadOrDying() || mc().gui.screen() instanceof DeathScreen;
		if (!dead) { queued = false; respawnSent = false; ticks = 0; return; }
		if (respawnSent) return;
		if (!queued) {
			queued = true;
			ticks = delay.getInt();
			if (deathWaypoint.get() && !UnluckyClient.INSTANCE.modules.get(Waypoints.class).isEnabled()) {
				String dimension = WaypointManager.currentDimension();
				if (dimension != null) WaypointManager.addDeath(mc().player.blockPosition(), dimension,
						0xFFFF5555, 3, "Death " + LocalTime.now().format(TIME));
			}
		}
		if (ticks-- > 0) return;
		if (deathToast.get()) UnluckyClient.INSTANCE.notifications.add("AutoRespawn",
				"Respawning", new ItemStack(Items.TOTEM_OF_UNDYING));
		if (closeGui.get()) mc().gui.setScreen(null);
		respawnSent = true;
		mc().player.respawn();
	}

	@Override protected void onDisable() { queued = false; respawnSent = false; ticks = 0; }
}
