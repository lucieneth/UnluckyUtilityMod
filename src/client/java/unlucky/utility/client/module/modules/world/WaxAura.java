package unlucky.utility.client.module.modules.world;

import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.WorldScan;

/**
 * Waxes every unwaxed sign within reach using honeycomb from your hotbar.
 * Inspired by Stardust's WaxAura.
 */
public class WaxAura extends Module {
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between waxing", 4, 1, 20, 1));

	private int ticksUntilAction;
	private boolean warnedNoHoneycomb;

	public WaxAura() {
		super("WaxAura", "Waxes signs in reach", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		warnedNoHoneycomb = false;
		ticksUntilAction = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null || --ticksUntilAction > 0) {
			return;
		}
		ticksUntilAction = (int) Math.round(delay.get());

		double reach = mc().player.blockInteractionRange();
		for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(reach)) {
			if (!(blockEntity instanceof SignBlockEntity sign) || sign.isWaxed()) {
				continue;
			}
			int slot = InteractUtil.findHotbarItem(Items.HONEYCOMB);
			if (slot < 0) {
				if (!warnedNoHoneycomb) {
					warnedNoHoneycomb = true;
					ChatUtil.info("§cWaxAura needs honeycomb in your hotbar.");
				}
				return;
			}
			warnedNoHoneycomb = false;
			InteractUtil.withHotbarSlot(slot, () -> InteractUtil.useOnBlock(sign.getBlockPos(), Direction.UP));
			return; // one sign per action tick
		}
	}
}
