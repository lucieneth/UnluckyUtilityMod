package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/** Removes selected fog types (applied in {@code FogRendererMixin}). */
public class NoFog extends Module {
	public final BooleanSetting distance = add(new BooleanSetting("Distance", "Far render-distance fog", true));
	public final BooleanSetting water = add(new BooleanSetting("Water", "Underwater fog", true));
	public final BooleanSetting lava = add(new BooleanSetting("Lava", "Lava fog", true));
	public final BooleanSetting powderSnow = add(new BooleanSetting("Powder snow", "Powder snow fog", true));
	public final BooleanSetting blindness = add(new BooleanSetting("Blindness", "Blindness / darkness fog", true));

	public NoFog() {
		super("NoFog", "Removes fog", Category.RENDER);
	}
}
