package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
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

	/** > 0 means "reel in when this hits 0"; then the recast timer takes over. */
	private int reelTimer;
	private int recastTimer;

	public AutoFish() {
		super("AutoFish", "Reels in and recasts when a fish bites", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		reelTimer = 0;
		recastTimer = 0;
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
		if (dx * dx + dy * dy + dz * dz > 4.0) {
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
		if (reelTimer > 0 && --reelTimer == 0) {
			if (castOrReel(player)) {
				recastTimer = recast.get() ? recastDelay.getInt() : 0;
			}
			return;
		}
		if (recastTimer > 0 && --recastTimer == 0 && player.fishing == null) {
			castOrReel(player);
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
		return true;
	}

	private InteractionHand rodHand(LocalPlayer player) {
		if (player.getMainHandItem().getItem() instanceof FishingRodItem) {
			return InteractionHand.MAIN_HAND;
		}
		if (player.getOffhandItem().getItem() instanceof FishingRodItem) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}
}
