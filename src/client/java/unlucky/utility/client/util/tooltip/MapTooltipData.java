package unlucky.utility.client.util.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.saveddata.maps.MapId;

/** Carries a filled map's id so the client component can blit its texture. */
public record MapTooltipData(MapId mapId) implements TooltipComponent {
}
