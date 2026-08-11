package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Slows hunger drain. Exhaustion is computed <em>server-side</em> — the client's
 * own {@code causeFoodExhaustion} does nothing — so this works by shaping what we
 * report, in {@code LocalPlayerMixin}:
 * <ul>
 *   <li><b>onGround</b> — the server detects a jump by seeing the flag go
 *       true → false. Always claiming to be grounded means it never charges
 *       you the 0.05/0.2 jump exhaustion.</li>
 *   <li><b>sprint</b> — the server charges 0.1/m only while <em>it</em> thinks
 *       you're sprinting, which it learns from a separate command packet. Not
 *       sending it means free sprinting.</li>
 * </ul>
 * This reduces drain, it doesn't stop it: eating, healing and swimming still cost.
 */
public class AntiHunger extends Module {
	public final BooleanSetting disableFalling = add(new BooleanSetting("Disable while falling", "Do not spoof ground while falling", true));
	public final BooleanSetting disableSwimming = add(new BooleanSetting("Disable while swimming", "Do not spoof ground while swimming", true));
	public final BooleanSetting disableElytra = add(new BooleanSetting("Disable while elytra", "Do not spoof ground while gliding", true));
	public final BooleanSetting spoofGround = add(new BooleanSetting("Spoof onGround",
			"Never report leaving the ground, so jumps cost no exhaustion", true));
	public final BooleanSetting spoofSprint = add(new BooleanSetting("Spoof sprint",
			"Never report sprinting. Costs you sprint knockback on hits", true));

	public AntiHunger() {
		super("AntiHunger", "Reduces hunger drain from jumping and sprinting", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		// we're about to stop reporting sprint state; make sure the server's
		// idea of it doesn't stay stuck at "sprinting"
		sendSprint(false);
	}

	@Override
	protected void onDisable() {
		sendSprint(true); // hand the real state back
	}

	private void sendSprint(boolean sprinting) {
		LocalPlayer player = mc().player;
		if (!spoofSprint.get() || player == null || mc().getConnection() == null || !player.isSprinting()) {
			return;
		}
		mc().getConnection().send(new ServerboundPlayerCommandPacket(player, sprinting
				? ServerboundPlayerCommandPacket.Action.START_SPRINTING
				: ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
	}

	public boolean spoofsGround(LocalPlayer player) {
		if (!isEnabled() || !spoofGround.get()) return false;
		if (disableFalling.get() && player.fallDistance > 0.0f) return false;
		if (disableSwimming.get() && player.isSwimming()) return false;
		return !disableElytra.get() || !player.isFallFlying();
	}
}
