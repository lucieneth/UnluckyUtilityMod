package unlucky.utility.test.module;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;

/**
 * One in-world test of specific modules. ModuleSmokeTest runs it inside the shared world,
 * after the scene is built, whenever the unlucky.test filter selects one of the modules
 * it covers (or its whole category). Register new ones in {@link ModuleTests#ALL}.
 */
public interface ModuleTest {
	/** The modules this test exercises; selecting any of them runs it. */
	List<Class<? extends Module>> covers();

	/** A category that runs this test whole, on top of {@link #covers()}; null for none. */
	default Category category() {
		return null;
	}

	/** Runs in the shared singleplayer world, with the scene from buildScene around the player. */
	void run(ClientGameTestContext context, TestServerContext server);
}
