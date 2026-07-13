package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * HUD toast avoidance: exposes how many of the 5 toast slots are free so
 * {@code HudManager} can slide top-right widgets down while toasts (module
 * toggles, the music "now playing" card, advancements) are on screen.
 */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
	@Invoker("freeSlotCount")
	int unlucky$freeSlotCount();
}
