package unlucky.utility.client.module.modules.visuals;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

public class Fullbright extends Module {
	public final NumberSetting intensity = add(new NumberSetting("Intensity", "How bright the world becomes", 1.0, 0.1, 1.0, 0.05));

	public Fullbright() {
		super("Fullbright", "Lights up the whole world", Category.RENDER);
	}
}

