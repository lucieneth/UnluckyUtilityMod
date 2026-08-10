package unlucky.utility.client.module.modules.visuals;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;

public class NoHurtCam extends Module {
	public NoHurtCam() {
		super("NoHurtCam", "Removes the camera tilt when taking damage", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}
}

