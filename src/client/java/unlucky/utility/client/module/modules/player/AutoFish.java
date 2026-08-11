package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.FishingRodItem;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Casts, waits for the bite, reels in, casts again.
 *
 * <p>The bite is detected from the <b>splash sound packet</b> rather than by
 * watching the bobber's private state: the server sends
 * {@code entity.fishing_bobber.splash} at the bobber's position the moment a
 * fish bites, which is both authoritative and cheap. We check it landed near
 * <em>our</em> bobber, so a neighbour's catch doesn't reel our line.
 *
 * <p>Reel and recast are separated by randomised delays — a fixed cadence is
 * the single most obvious tell.
 */
public class AutoFish extends Module {
	public final BooleanSetting recast = add(new BooleanSetting("Recast",
			"Throw the line back out after reeling in", true));
	public final NumberSetting reelMin = add(new NumberSetting("Reel delay min",
			"Ticks to wait before reeling in", 4.0, 0.0, 40.0, 1.0));
	public final NumberSetting reelMax = add(new NumberSetting("Reel delay max",
			"Upper bound of the reel wait — randomised in between", 10.0, 0.0, 40.0, 1.0));
	public final NumberSetting recastDelay = add(new NumberSetting("Recast delay",
			"Ticks between reeling in and casting again", 12.0, 1.0, 60.0, 1.0));
	public final NumberSetting recastVariance = add(new NumberSetting("Recast delay variance",
			"Extra random ticks before casting again", 4.0, 0.0, 40.0, 1.0));
	public final BooleanSetting pauseInGui = add(new BooleanSetting("Pause in GUIs",
			"Do not reel or cast while another screen is open", true));
	public final BooleanSetting autoSelect = add(new BooleanSetting("Auto select rod",
			"Select a safe fishing rod from the hotbar when needed", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the previously selected hotbar slot after casting", true));
	public final BooleanSetting autoCast = add(new BooleanSetting("Auto cast when no hook exists",
			"Cast a rod when the line is not already out", true));
	public final NumberSetting castDelay = add(new NumberSetting("Cast delay", "Ticks before the first automatic cast",
			8, 0, 100, 1), autoCast::get);
	public final NumberSetting castVariance = add(new NumberSetting("Cast delay variance",
			"Extra random ticks before casting", 3, 0, 40, 1), autoCast::get);
	public final BooleanSetting antiBreak = add(new BooleanSetting("Anti break",
			"Do not use rods near breaking", true));
	public final NumberSetting minimumDurability = add(new NumberSetting("Minimum durability",
			"Minimum rod durability percentage", 10, 1, 100, 1), antiBreak::get);
	public final BooleanSetting stopWhenFull = add(new BooleanSetting("Stop when inventory full",
			"Do not keep fishing when there is nowhere for catches", false));
	public final BooleanSetting recoverHook = add(new BooleanSetting("Recover stuck hook",
			"Reel in an inactive hook after its timeout", true));
	public final NumberSetting hookTimeout = add(new NumberSetting("Hook timeout",
			"Seconds before an unproductive hook is recovered", 90, 10, 600, 5), recoverHook::get);
	public final BooleanSetting useOffhand = add(new BooleanSetting("Use offhand rod",
			"Prefer a rod already held in the offhand", true));
	public final NumberSetting soundRange = add(new NumberSetting("Sound detection range",
			"Maximum distance between splash and our bobber", 2, 1, 8, 0.5));

	/** > 0 means "reel in when this hits 0"; then the recast timer takes over. */
	private int reelTimer;
	private int recastTimer;
	private int castTimer;
	private int hookTicks;
	private int previousSlot = -1;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public AutoFish() {
		super("AutoFish", "Reels in and recasts when a fish bites", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		reelTimer = 0;
		recastTimer = 0;
		castTimer = randomDelay(castDelay.getInt());
		hookTicks = 0;
		previousSlot = -1;
	}

	/** Called from {@code ClientPacketListenerMixin} for every incoming sound. */
	public void onSound(ClientboundSoundPacket packet) {
		LocalPlayer player = mc().player;
		if (player == null || reelTimer > 0
				|| packet.getSound().value() != SoundEvents.FISHING_BOBBER_SPLASH) {
			return;
		}
		FishingHook hook = player.fishing;
		if (hook == null) {
			return;
		}
		// the splash has to be ours — within a couple of blocks of our own bobber
		double dx = packet.getX() - hook.getX();
		double dy = packet.getY() - hook.getY();
		double dz = packet.getZ() - hook.getZ();
		if (dx * dx + dy * dy + dz * dz > soundRange.get() * soundRange.get()) {
			return;
		}
		int min = reelMin.getInt();
		int max = Math.max(min, reelMax.getInt());
		reelTimer = 1 + min + (min == max ? 0 : player.getRandom().nextInt(max - min + 1));
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null) {
			return;
		}
		if (pauseInGui.get() && mc().gui.screen() != null) {
			return;
		}
		// The sharpest conflict of the lot: AutoEat eats by holding the use key, and casting
		// a rod is the same right-click. Left alone the two reel and recast through a meal.
		if (AutoEat.pauses(pauseOnEat)) {
			return;
		}
		if (stopWhenFull.get() && inventoryFull(player)) return;
		if (player.fishing != null) {
			hookTicks++;
			if (recoverHook.get() && hookTicks >= hookTimeout.getInt() * 20) {
				if (castOrReel(player)) recastTimer = recast.get() ? randomRecastDelay() : 0;
				hookTicks = 0;
			}
		} else {
			hookTicks = 0;
		}
		if (reelTimer > 0 && --reelTimer == 0) {
			if (castOrReel(player)) {
				recastTimer = recast.get() ? randomRecastDelay() : 0;
			}
			return;
		}
		if (recastTimer > 0 && --recastTimer == 0 && player.fishing == null) {
			castOrReel(player);
		} else if (autoCast.get() && player.fishing == null && recastTimer == 0 && --castTimer <= 0) {
			castOrReel(player);
			castTimer = randomDelay(castDelay.getInt());
		}
	}

	/** Right-clicks the rod: pulls the line in when it's out, throws it when it isn't. */
	private boolean castOrReel(LocalPlayer player) {
		InteractionHand hand = rodHand(player);
		if (hand == null) {
			return false;
		}
		mc().gameMode.useItem(player, hand);
		player.swing(hand);
		if (hand == InteractionHand.MAIN_HAND && swapBack.get() && previousSlot >= 0) {
			player.getInventory().setSelectedSlot(previousSlot);
			previousSlot = -1;
		}
		return true;
	}

	private InteractionHand rodHand(LocalPlayer player) {
		if (safeRod(player.getMainHandItem())) {
			return InteractionHand.MAIN_HAND;
		}
		if (useOffhand.get() && safeRod(player.getOffhandItem())) {
			return InteractionHand.OFF_HAND;
		}
		if (autoSelect.get()) {
			Inventory inventory = player.getInventory();
			for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
				if (!safeRod(inventory.getItem(i))) continue;
				previousSlot = inventory.getSelectedSlot();
				inventory.setSelectedSlot(i);
				return InteractionHand.MAIN_HAND;
			}
		}
		return null;
	}

	private boolean safeRod(ItemStack stack) {
		if (!(stack.getItem() instanceof FishingRodItem)) return false;
		return !antiBreak.get() || !stack.isDamageableItem()
				|| (stack.getMaxDamage() - stack.getDamageValue()) * 100.0 / stack.getMaxDamage()
						>= minimumDurability.get();
	}

	private int randomDelay(int base) {
		LocalPlayer player = mc().player;
		int variance = castVariance.getInt();
		return base + (player == null || variance == 0 ? 0 : player.getRandom().nextInt(variance + 1));
	}

	private int randomRecastDelay() {
		LocalPlayer player = mc().player;
		int variance = recastVariance.getInt();
		return recastDelay.getInt() + (player == null || variance == 0 ? 0 : player.getRandom().nextInt(variance + 1));
	}

	private static boolean inventoryFull(LocalPlayer player) {
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) if (stack.isEmpty()) return false;
		return true;
	}
}
