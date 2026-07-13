package unlucky.utility.client.util.tooltip;

import java.util.List;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Data-side tooltip carrier for a container's contents. {@code enderChest} picks the
 * ender-chest panel texture (and marks the source as the player's cached ender chest
 * rather than a stack's {@code CONTAINER} component). Turned into a grid renderer by
 * the {@code ClientTooltipComponentCallback} registered in {@code UnluckyClient}.
 */
public record ContainerTooltipData(List<ItemStack> items, boolean enderChest) implements TooltipComponent {
}
