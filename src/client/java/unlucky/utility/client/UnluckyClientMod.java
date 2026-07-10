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
		UnluckyClient.INSTANCE.init();

		ClientTickEvents.END_CLIENT_TICK.register(client -> UnluckyClient.INSTANCE.tick());
		HudElementRegistry.addLast(id("hud"), (graphics, deltaTracker) ->
				UnluckyClient.INSTANCE.renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true)));

		LOGGER.info("Unlucky Client initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
