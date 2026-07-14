package unlucky.utility.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The bound key behind a mapping. KeyMapping exposes {@code isDown()} but no
 * getter for the key itself, and InventoryMove needs the raw GLFW code so it can
 * poll the hardware directly while a screen has eaten the keyboard.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("key")
	InputConstants.Key unlucky$key();
}
