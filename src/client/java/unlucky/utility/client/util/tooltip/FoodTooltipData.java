package unlucky.utility.client.util.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * FoodOverlay's tooltip carrier: food value line for hovered food items.
 * Values are resolved eagerly by {@code ItemStackTooltipMixin} (it caches per
 * hovered stack) so the renderer stays dumb.
 *
 * @param nutrition  hunger points restored
 * @param saturation saturation points granted
 * @param rotten     food applies the Hunger effect (rotten flesh & co) —
 *                   switches to the rotten drumsticks and red saturation icons
 */
public record FoodTooltipData(int nutrition, float saturation, boolean rotten) implements TooltipComponent {
}
