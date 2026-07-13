package unlucky.utility.client.util.tooltip;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** Carries a written book's first page so the client component can draw it on the parchment. */
public record BookTooltipData(Component firstPage) implements TooltipComponent {
}
