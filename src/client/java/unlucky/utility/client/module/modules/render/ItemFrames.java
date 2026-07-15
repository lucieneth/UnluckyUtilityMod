package unlucky.utility.client.module.modules.render;

import net.minecraft.world.entity.decoration.ItemFrame;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Stops distant item frames from being drawn.
 *
 * <p>An item frame is an <i>entity</i>, not a block entity: nothing about it is
 * baked into the chunk mesh, so every frame in view is re-extracted and
 * re-submitted every single rendered frame — its block model, the item model
 * inside it, and for map frames an entire map render. Vanilla culls them only by
 * frustum, so a storage room whose walls are papered in labelled frames pays that
 * cost for all of them at once, and the FPS loss scales linearly with how many
 * you built. Capping the distance is the whole fix: the ones across the room are
 * unreadable anyway.
 *
 * <p>Maps get their own (shorter) cap because they're the expensive kind — a map
 * frame re-renders its texture where an item frame just draws a model.
 */
public class ItemFrames extends Module {
	public final NumberSetting distance = add(new NumberSetting("Distance",
			"Item frames further away than this aren't drawn", 24, 4, 128, 4));
	public final BooleanSetting maps = add(new BooleanSetting("Cull maps",
			"Also cap map frames, which cost the most to draw", true));
	public final NumberSetting mapDistance = add(new NumberSetting("Map distance",
			"Distance cap for frames holding a map", 16, 4, 128, 4));
	public final BooleanSetting keepEmpty = add(new BooleanSetting("Keep empty frames",
			"Never cull frames holding nothing (they're nearly free to draw)", true));

	public ItemFrames() {
		super("ItemFrames", "Cull distant item frames — big FPS win in storage rooms", Category.RENDER);
	}

	/**
	 * True when this frame is far enough away to skip. Called from the render
	 * dispatcher's visibility check, so returning true costs us the whole entity:
	 * no extract, no model resolve, no submit.
	 */
	public boolean cull(ItemFrame frame, double cameraX, double cameraY, double cameraZ) {
		if (!isEnabled()) {
			return false;
		}
		boolean empty = frame.getItem().isEmpty();
		if (empty && keepEmpty.get()) {
			return false;
		}
		boolean isMap = frame.getItem().has(net.minecraft.core.component.DataComponents.MAP_ID);
		if (isMap && !maps.get()) {
			return false;
		}
		double limit = isMap ? mapDistance.get() : distance.get();
		double dx = frame.getX() - cameraX;
		double dy = frame.getY() - cameraY;
		double dz = frame.getZ() - cameraZ;
		return dx * dx + dy * dy + dz * dz > limit * limit;
	}
}
