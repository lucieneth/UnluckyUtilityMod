package unlucky.utility.client.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * FoodOverlay: vanilla has no exhaustion getter at all (only
 * {@code addExhaustion}); the exhaustion bar reads the integrated-server
 * player's value through this.
 */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
	@Accessor("exhaustionLevel")
	float unlucky$exhaustion();
}
