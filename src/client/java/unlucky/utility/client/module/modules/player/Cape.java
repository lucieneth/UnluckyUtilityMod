package unlucky.utility.client.module.modules.player;

import java.util.List;

import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.CapeManager;

/**
 * Custom capes. Pick a folder (each subfolder of {@code assets/unlucky/textures/capes/}
 * is its own group — e.g. "mojang" holds all the official capes) then a cape from
 * it, and it's swapped onto your own skin. The swap happens on
 * {@link net.minecraft.client.player.AbstractClientPlayer#getSkin()}, so the vanilla
 * cape/elytra layers render it with their exact physics — always 1:1 with the original.
 */
public class Cape extends Module {
	public final BooleanSetting hideCape = add(new BooleanSetting("Hide real cape",
			"Hide your own cape entirely, so nothing renders over your back", false));
	public final ModeSetting folder = add(new ModeSetting("Folder",
			"Which cape folder to browse", "mojang", "mojang"));
	public final ModeSetting cape = add(new ModeSetting("Cape",
			"Which cape to wear (mojang capes stream in on first use)", "None", "None"));

	private String lastFolder = "";
	private int lastRevision = -1;

	public Cape() {
		super("Cape", "Wear a custom cape, or hide your own", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		sync();
	}

	@Override
	public void onTick() {
		sync();
	}

	/** Keeps the folder/cape option lists in step with what's available (incl. the live GitHub pack). */
	private void sync() {
		int revision = CapeManager.revision();
		if (revision != lastRevision) {
			lastRevision = revision;
			List<String> groups = CapeManager.groups();
			if (!groups.isEmpty()) {
				folder.setModes(groups);
				if (!groups.contains(folder.get())) {
					folder.set(groups.get(0));
				}
				lastFolder = ""; // force the cape list to rebuild for the (possibly new) folder
			}
		}
		if (!folder.get().equals(lastFolder)) {
			List<String> options = CapeManager.optionNames(folder.get());
			cape.setModes(options);
			if (!options.contains(cape.get())) {
				cape.set("None");
			}
			lastFolder = folder.get();
		}
	}

	/**
	 * Returns the local player's skin with the cape/elytra swapped or removed, or
	 * the skin unchanged. Called from the getSkin mixin.
	 */
	public PlayerSkin apply(PlayerSkin base) {
		if (base == null) {
			return base;
		}
		if (!cape.is("None")) {
			// reuse the cape texture for the elytra — that's how official capes
			// carry onto the elytra, so it stays faithful. null = still streaming.
			ClientAsset.Texture tex = CapeManager.textureFor(folder.get(), cape.get());
			if (tex != null) {
				return new PlayerSkin(base.body(), tex, tex, base.model(), base.secure());
			}
		}
		if (hideCape.get()) {
			return new PlayerSkin(base.body(), null, base.elytra(), base.model(), base.secure());
		}
		return base;
	}
}
