package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * AFK long-distance travel companion: periodic progress reports and
 * auto-disconnect safeties. Inspired by Stardust's RoadTrip.
 */
public class RoadTrip extends Module {
	public final NumberSetting reportMinutes = add(new NumberSetting("Report every", "Minutes between progress reports", 5, 1, 30, 1));
	public final BooleanSetting dcOnPlayer = add(new BooleanSetting("DC on player", "Disconnect when another player appears", true));
	public final BooleanSetting dcLowElytra = add(new BooleanSetting("DC low elytra", "Disconnect when elytra is nearly broken", true));
	public final BooleanSetting dcNoRockets = add(new BooleanSetting("DC no rockets", "Disconnect when flying with no rockets left", false));

	private Vec3 lastReportPos;
	private long lastReportTime;
	private Vec3 startPos;
	private int ticksUntilCheck;

	public RoadTrip() {
		super("RoadTrip", "AFK travel reports and safeties", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		startPos = null;
		lastReportPos = null;
		ticksUntilCheck = 0;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || --ticksUntilCheck > 0) {
			return;
		}
		ticksUntilCheck = 20; // once a second

		Vec3 pos = mc().player.position();
		long now = System.currentTimeMillis();
		if (startPos == null) {
			startPos = pos;
			lastReportPos = pos;
			lastReportTime = now;
		}

		// safeties first
		if (dcOnPlayer.get()) {
			for (AbstractClientPlayer player : mc().level.players()) {
				if (player != mc().player) {
					disconnect("Player spotted: " + player.getName().getString() + " at " + format(player.position()));
					return;
				}
			}
		}
		if (dcLowElytra.get()) {
			ItemStack chest = mc().player.getItemBySlot(EquipmentSlot.CHEST);
			if (chest.is(Items.ELYTRA) && chest.isDamageableItem()
					&& chest.getMaxDamage() - chest.getDamageValue() < chest.getMaxDamage() * 0.08) {
				disconnect("Elytra nearly broken");
				return;
			}
		}
		if (dcNoRockets.get() && mc().player.isFallFlying()
				&& mc().player.getInventory().countItem(Items.FIREWORK_ROCKET) == 0) {
			disconnect("Out of rockets mid-flight");
			return;
		}

		// progress report
		long intervalMs = (long) (reportMinutes.get() * 60_000);
		if (now - lastReportTime >= intervalMs) {
			double legDistance = horizontal(pos, lastReportPos);
			double totalDistance = horizontal(pos, startPos);
			double blocksPerMinute = legDistance / ((now - lastReportTime) / 60_000.0);
			ChatUtil.info(String.format("§7RoadTrip: §f%s §8| §7%.0f blocks/min §8| §7%.0f total",
					format(pos), blocksPerMinute, totalDistance));
			lastReportPos = pos;
			lastReportTime = now;
		}
	}

	private void disconnect(String reason) {
		ChatUtil.info("§cRoadTrip disconnecting: " + reason);
		setEnabledSilently(false);
		mc().disconnectFromWorld(Component.literal("[Unlucky] RoadTrip: " + reason));
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static String format(Vec3 pos) {
		return (int) pos.x + " " + (int) pos.y + " " + (int) pos.z;
	}
}
