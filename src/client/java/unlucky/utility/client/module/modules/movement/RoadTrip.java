package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.FakePlayerEntity;
import unlucky.utility.client.util.MoveUtil;

/**
 * AFK long-distance travel helper: periodic progress reports and
 * auto-disconnect safeties. Inspired by Stardust's RoadTrip.
 */
public class RoadTrip extends Module {
	public final NumberSetting reportMinutes = add(new NumberSetting("Report every", "Minutes between progress reports", 5, 1, 30, 1));
	public final BooleanSetting dcOnPlayer = add(new BooleanSetting("DC on player", "Disconnect when another player appears", true));
	public final NumberSetting playerRadius = add(new NumberSetting("Player detection radius",
			"Only react to players this close", 128, 8, 512, 8), dcOnPlayer::get);
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends", "Do not stop for friends", true),
			dcOnPlayer::get);
	public final BooleanSetting dcLowElytra = add(new BooleanSetting("DC low elytra", "Disconnect when elytra is nearly broken", true));
	public final BooleanSetting dcNoRockets = add(new BooleanSetting("DC no rockets", "Disconnect when flying with no rockets left", false));
	public final NumberSetting minimumRockets = add(new NumberSetting("Minimum rockets", "Rocket reserve before stopping",
			0, 0, 256, 1), dcNoRockets::get);
	public final NumberSetting elytraDurability = add(new NumberSetting("Elytra durability %",
			"Stop when the elytra reaches this remaining durability", 8, 1, 50, 1), dcLowElytra::get);
	public final ModeSetting safetyAction = add(new ModeSetting("Safety action", "What a safety rule does",
			"Disconnect", "Disconnect", "Stop movement", "Disable module"));
	public final BooleanSetting chatReason = add(new BooleanSetting("Chat reason", "Report the rule that stopped travel", true));
	public final BooleanSetting stuckDetection = add(new BooleanSetting("Stuck detection",
			"Watch for too little movement while travel keys are held", true));
	public final NumberSetting stuckSeconds = add(new NumberSetting("Stuck after",
			"Seconds without meaningful horizontal travel before acting", 60, 10, 600, 5), stuckDetection::get);
	public final NumberSetting stuckDistance = add(new NumberSetting("Minimum travel",
			"Horizontal blocks that count as progress during the stuck window", 8, 1, 64, 1), stuckDetection::get);
	public final ModeSetting stuckAction = add(new ModeSetting("On stuck", "Warn only or run the normal safety action",
			"Warn", "Warn", "Safety action"), stuckDetection::get);

	private Vec3 lastReportPos;
	private long lastReportTime;
	private Vec3 startPos;
	private Vec3 stuckStartPos;
	private int stuckTicks;
	private int ticksUntilCheck;

	public RoadTrip() {
		super("RoadTrip", "AFK travel reports and safeties", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		startPos = null;
		lastReportPos = null;
		stuckStartPos = null;
		stuckTicks = 0;
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
				if (player != mc().player && !(player instanceof FakePlayerEntity)
						&& (!ignoreFriends.get() || !unlucky.utility.client.util.FriendManager.isFriend(player.getUUID()))
						&& player.distanceTo(mc().player) <= playerRadius.get()) {
					stop("Player spotted: " + player.getName().getString() + " at " + format(player.position()));
					return;
				}
			}
		}
		if (dcLowElytra.get()) {
			ItemStack chest = mc().player.getItemBySlot(EquipmentSlot.CHEST);
			if (chest.is(Items.ELYTRA) && chest.isDamageableItem()
					&& chest.getMaxDamage() - chest.getDamageValue() < chest.getMaxDamage() * elytraDurability.get() / 100.0) {
				stop("Elytra nearly broken");
				return;
			}
		}
		if (dcNoRockets.get() && mc().player.isFallFlying()
				&& mc().player.getInventory().countItem(Items.FIREWORK_ROCKET) <= minimumRockets.getInt()) {
			stop("Rocket reserve reached mid-flight");
			return;
		}
		checkStuck(pos);
		if (!isEnabled()) return;

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

	private void stop(String reason) {
		if (chatReason.get()) ChatUtil.info("§cRoadTrip: " + reason);
		if (safetyAction.is("Stop movement")) {
			mc().player.setDeltaMovement(Vec3.ZERO);
		}
		setEnabledSilently(false);
		if (safetyAction.is("Disconnect")) {
			mc().disconnectFromWorld(Component.literal("[Unlucky] RoadTrip: " + reason));
		}
	}

	/** Samples once a second, so a wall/collision is detected without reacting to a brief turn. */
	private void checkStuck(Vec3 pos) {
		if (!stuckDetection.get() || !MoveUtil.hasInput(mc().player)) {
			stuckStartPos = pos;
			stuckTicks = 0;
			return;
		}
		if (stuckStartPos == null || horizontal(pos, stuckStartPos) >= stuckDistance.get()) {
			stuckStartPos = pos;
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks < stuckSeconds.getInt()) return;
		String reason = "No progress for " + stuckSeconds.getInt() + " seconds";
		stuckStartPos = pos;
		stuckTicks = 0;
		if (stuckAction.is("Safety action")) stop(reason);
		else if (chatReason.get()) ChatUtil.info("§eRoadTrip: " + reason);
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
