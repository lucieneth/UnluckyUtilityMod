package unlucky.utility.client.util.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/** Carries a banner stack so the client component can render it scaled up. */
public record BannerTooltipData(ItemStack banner) implements TooltipComponent {
}
