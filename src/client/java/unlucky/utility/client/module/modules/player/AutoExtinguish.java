package unlucky.utility.client.module.modules.player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;

/**
 * On fire? Drops a water bucket at your feet and scoops it back up once the
 * fire is out — before it flows everywhere.
 */
public class AutoExtinguish extends Module {
	private enum Phase {
		IDLE, PLACED
	}

	private Phase phase = Phase.IDLE;
	private BlockPos placedAt;
	private int ticksInPhase;
	private boolean warnedNoBucket;

	public AutoExtinguish() {
		super("AutoExtinguish", "Water-buckets fire away, then cleans up", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		phase = Phase.IDLE;
		warnedNoBucket = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			phase = Phase.IDLE;
			return;
		}

		switch (phase) {
			case IDLE -> {
				if (!mc().player.isOnFire() || mc().player.isInWater() || !mc().player.onGround()) {
					return;
				}
				if (mc().level.dimension() == net.minecraft.world.level.Level.NETHER) {
					return; // water evaporates in the nether, nothing we can do
				}
				int slot = InteractUtil.findHotbarItem(Items.WATER_BUCKET);
				if (slot < 0) {
					if (!warnedNoBucket) {
						warnedNoBucket = true;
						ChatUtil.info("§cAutoExtinguish needs a water bucket in your hotbar.");
					}
					return;
				}
				warnedNoBucket = false;
				useLookingDown(slot);
				placedAt = mc().player.blockPosition();
				phase = Phase.PLACED;
				ticksInPhase = 0;
			}
			case PLACED -> {
				ticksInPhase++;
				// wait for the fire to be out (plus a beat), then reclaim
				if ((!mc().player.isOnFire() && ticksInPhase >= 8) || ticksInPhase > 60) {
					int slot = InteractUtil.findHotbarItem(Items.BUCKET);
					boolean waterStillThere = placedAt != null
							&& mc().level.getBlockState(placedAt).is(Blocks.WATER);
					if (slot >= 0 && waterStillThere && placedAt.closerToCenterThan(mc().player.position(), 4.0)) {
						useLookingDown(slot);
					}
					phase = Phase.IDLE;
				}
			}
		}
	}

	/** Uses the hotbar slot while aiming straight down, restoring the real pitch. */
	private void useLookingDown(int slot) {
		float oldPitch = mc().player.getXRot();
		mc().player.setXRot(90.0f);
		try {
			InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
		} finally {
			mc().player.setXRot(oldPitch);
		}
	}
}
