package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Removes the movement penalties the client applies to itself.
 *
 * <p>All three are client-side multipliers folded into the movement input before
 * it ever becomes a packet, so cancelling them simply means we walk at full speed
 * while eating, blocking or drawing a bow:
 * <ul>
 *   <li>{@code items} — {@code LocalPlayer.modifyInput} scales the move vector by
 *       {@code itemUseSpeedMultiplier()} while an item is in use.</li>
 *   <li>{@code web} — {@code Player.makeStuckInBlock} sets the stuck multiplier
 *       (cobwebs, sweet berries, powder snow).</li>
 *   <li>{@code blocks} — {@code Player.getBlockSpeedFactor}, the soul sand and
 *       honey block drag. Only factors below 1 are lifted, so speed <i>boosts</i>
 *       (soul speed) still apply.</li>
 * </ul>
 */
public class NoSlow extends Module {
	public final BooleanSetting consumables = add(new BooleanSetting("Consumables", "Full speed while eating or drinking", true));
	public final BooleanSetting bows = add(new BooleanSetting("Bows", "Full speed while drawing bows", true));
	public final BooleanSetting crossbows = add(new BooleanSetting("Crossbows", "Full speed while loading crossbows", true));
	public final BooleanSetting shields = add(new BooleanSetting("Shields", "Full speed while blocking", true));
	public final BooleanSetting tridents = add(new BooleanSetting("Tridents / Spears", "Full speed while charging tridents", true));
	public final BooleanSetting spyglass = add(new BooleanSetting("Spyglass", "Full speed while using a spyglass", true));
	public final BooleanSetting otherUseItems = add(new BooleanSetting("Other use items", "Full speed for remaining use items", false));
	public final BooleanSetting cobweb = add(new BooleanSetting("Cobweb", "Ignore cobweb drag", false));
	public final BooleanSetting berries = add(new BooleanSetting("Sweet berry bush", "Ignore berry bush drag", false));
	public final BooleanSetting powderSnow = add(new BooleanSetting("Powder snow", "Ignore powder-snow drag", false));
	public final BooleanSetting honey = add(new BooleanSetting("Honey", "Ignore honey block drag", false));
	public final BooleanSetting soulSand = add(new BooleanSetting("Soul sand / soul soil", "Ignore soul sand drag", false));
	public final NumberSetting slowMultiplier = add(new NumberSetting("Slow multiplier",
			"Remaining slowdown when a selected source applies (0 is full cancellation)", 0, 0, 100, 5));

	public NoSlow() {
		super("NoSlow", "Cancel the client's own movement slowdowns", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	public boolean cancelItemUse(ItemStack stack) {
		if (!isEnabled() || stack.isEmpty()) return false;
		if (stack.getItem() instanceof BowItem) return bows.get();
		if (stack.getItem() instanceof CrossbowItem) return crossbows.get();
		if (stack.getItem() instanceof ShieldItem) return shields.get();
		if (stack.getItem() instanceof TridentItem) return tridents.get();
		if (stack.getItem() instanceof SpyglassItem) return spyglass.get();
		return stack.get(net.minecraft.core.component.DataComponents.FOOD) != null
				? consumables.get() : otherUseItems.get();
	}

	public boolean cancelStuck(BlockState state) {
		return isEnabled() && ((state.is(Blocks.COBWEB) && cobweb.get())
				|| (state.is(Blocks.SWEET_BERRY_BUSH) && berries.get())
				|| (state.is(Blocks.POWDER_SNOW) && powderSnow.get()));
	}

	public boolean cancelBlockDrag(BlockState state) {
		return isEnabled() && ((state.is(Blocks.HONEY_BLOCK) && honey.get())
				|| ((state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) && soulSand.get()));
	}

	public float multiplier() {
		return 1.0f - slowMultiplier.getFloat() / 100.0f;
	}
}
