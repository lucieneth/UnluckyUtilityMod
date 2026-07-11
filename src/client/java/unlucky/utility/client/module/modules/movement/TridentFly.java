package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Riptide anywhere — no rain, no water, no Riptide enchant.
 *
 * <p>Vanilla gates the dash behind {@code isInWaterOrRain()} and the enchant
 * level, and its {@code TridentItem.releaseUsing} would <em>throw</em> an
 * unenchanted trident. So we don't go near that path: we apply the dash
 * ourselves on right-click and cancel the vanilla use entirely
 * ({@code MinecraftMixin}). Motion is client-authoritative, so the server just
 * accepts where we end up; only the spin is cosmetic.
 */
public class TridentFly extends Module {
	public final BooleanSetting anyItem = add(new BooleanSetting("Any item",
			"Dash while holding anything, not just a trident", false));
	public final NumberSetting strength = add(new NumberSetting("Strength",
			"How hard each dash throws you", 3.0, 0.5, 5.0, 0.1));
	public final NumberSetting cooldown = add(new NumberSetting("Cooldown",
			"Ticks to wait between dashes", 10.0, 0.0, 40.0, 1.0));
	public final BooleanSetting spin = add(new BooleanSetting("Spin animation",
			"Play the riptide spin while you fly", true));

	private int cooldownTicks;

	public TridentFly() {
		super("TridentFly", "Riptide flight without rain, water or the enchant", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		cooldownTicks = 0;
	}

	@Override
	public void onTick() {
		if (cooldownTicks > 0) {
			cooldownTicks--;
		}
	}

	/**
	 * Called from the right-click hook. Returns true when it dashed, which tells
	 * the caller to swallow the click so vanilla never charges (or throws) the trident.
	 */
	public boolean tryBoost() {
		LocalPlayer player = mc().player;
		if (player == null || cooldownTicks > 0 || player.isSpectator()) {
			return false;
		}
		ItemStack stack = heldTrident(player);
		if (stack == null) {
			return false;
		}
		Vec3 look = player.getLookAngle();
		player.setDeltaMovement(player.getDeltaMovement().add(look.scale(strength.get())));
		if (player.onGround()) {
			// nudge off the floor, or the dash scrapes along the ground
			player.setDeltaMovement(player.getDeltaMovement().add(0.0, 0.2, 0.0));
		}
		if (spin.get()) {
			player.startAutoSpinAttack(20, 0.0f, stack);
		}
		cooldownTicks = cooldown.getInt();
		return true;
	}

	/** The stack to dash with, or null if we shouldn't. */
	private ItemStack heldTrident(LocalPlayer player) {
		ItemStack main = player.getMainHandItem();
		ItemStack off = player.getOffhandItem();
		if (anyItem.get()) {
			return main.isEmpty() ? off : main;
		}
		if (main.getItem() instanceof TridentItem) {
			return main;
		}
		return off.getItem() instanceof TridentItem ? off : null;
	}
}
