package unlucky.utility.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnluckyClientMod implements ClientModInitializer {
	public static final String MOD_ID = "unlucky";
	public static final Logger LOGGER = LoggerFactory.getLogger("UnluckyClient");

	@Override
	public void onInitializeClient() {
		// Before anything else renders: see LogSpam for why another mod's logging is ours
		// to care about.
		unlucky.utility.client.util.LogSpam.muteLitematicaRenderSpam();
		unlucky.utility.client.network.CarpetBridge.register();
		UnluckyClient.INSTANCE.init();

		ClientTickEvents.END_CLIENT_TICK.register(client -> UnluckyClient.INSTANCE.tick());
		HudElementRegistry.addLast(id("hud"), (graphics, deltaTracker) ->
				UnluckyClient.INSTANCE.renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true)));

		// Last, and only when asked for: the audit force-loads every mixin target, so it must
		// not front-run anything that cares about class-load order.
		if (unlucky.utility.client.util.MixinAudit.ENABLED) {
			unlucky.utility.client.util.MixinAudit.run();
		}

		LOGGER.info("Unlucky Client initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
