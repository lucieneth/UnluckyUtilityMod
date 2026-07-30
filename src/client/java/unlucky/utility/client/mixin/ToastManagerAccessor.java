package unlucky.utility.client.mixin;

import java.util.BitSet;

import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * HUD toast avoidance: exposes which of the five toast slots are taken, so
 * {@code HudManager} can slide top-right widgets down past whatever is on screen.
 *
 * <p><b>Which slots, not how many.</b> Vanilla assigns a toast a slot for life and
 * never repacks: when the top one expires the ones below stay exactly where they are,
 * leaving a hole at the top. A count would say the stack had shrunk by one and our
 * widgets would slide up underneath toasts that had not moved — the bit set says where
 * the toasts actually end.
 */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
	@Accessor("occupiedSlots")
	BitSet unlucky$occupiedSlots();
}
