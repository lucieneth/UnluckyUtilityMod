package unlucky.utility.client.util.alts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import unlucky.utility.client.UnluckyClientMod;

/**
 * The saved alt list, persisted to {@code config/unlucky/alts.json} next to the
 * friends list and cape cache. Holds the accounts plus the Azure {@code
 * clientId} the Microsoft flow uses (see the alt-switcher setup — the user
 * registers their own app).
 *
 * <p><b>SENSITIVE FILE:</b> a Microsoft account's stored refresh/access tokens
 * effectively grant access to that account. Treat {@code alts.json} like a
 * password file — it is git-ignored, and it must not be shared. The switcher
 * UI surfaces this too.
 */
public final class AltManager {
	/**
	 * The Azure client id for the Microsoft device-code flow. Microsoft gates
	 * apps registered after ~2022 behind an approval form — a brand-new app
	 * (like Lucien's own {@code de9f4927-…}) gets "Invalid app registration" at
	 * {@code login_with_xbox} even though Xbox sign-in succeeds. So we default to
	 * a <b>grandfathered</b> client id (PandoraLauncher's, published in its
	 * open-source repo) that predates the gate and is accepted. A client id is a
	 * <em>public</em> OAuth identifier (no secret). Override in {@code alts.json}
	 * with your own id if you ever get an app approved. Note: with this id the
	 * Microsoft consent screen shows "PandoraLauncher".
	 */
	public static final String DEFAULT_CLIENT_ID = "e5226706-5096-431d-9516-ae48fe263401";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** On-disk shape: the account list + the user's Azure client id. */
	private static final class Store {
		List<AltAccount> accounts = new ArrayList<>();
		String clientId = "";
	}

	private static Store store;

	private AltManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky/alts.json");
	}

	private static Store store() {
		if (store != null) {
			return store;
		}
		store = new Store();
		Path file = file();
		if (Files.exists(file)) {
			try {
				Store loaded = GSON.fromJson(Files.readString(file), new TypeToken<Store>() {
				}.getType());
				if (loaded != null) {
					store = loaded;
					if (store.accounts == null) {
						store.accounts = new ArrayList<>();
					}
					if (store.clientId == null) {
						store.clientId = "";
					}
				}
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.error("Failed to load alts.json", e);
			}
		}
		return store;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(store()));
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.error("Failed to save alts.json", e);
		}
	}

	/** Live view of the saved accounts (order = add order; first is the preview skin). */
	public static List<AltAccount> accounts() {
		return store().accounts;
	}

	/** The Azure application (client) id the Microsoft device-code flow uses; falls back to the bundled default. */
	public static String clientId() {
		String id = store().clientId;
		return id == null || id.isBlank() ? DEFAULT_CLIENT_ID : id;
	}

	public static void setClientId(String clientId) {
		store().clientId = clientId == null ? "" : clientId.trim();
		save();
	}

	/** Adds (or replaces same-uuid) an account and saves; MS re-adds refresh the token. */
	public static void add(AltAccount account) {
		List<AltAccount> list = store().accounts;
		list.removeIf(a -> a.uuid().equals(account.uuid()));
		list.add(account);
		save();
	}

	public static void remove(UUID uuid) {
		if (store().accounts.removeIf(a -> a.uuid().equals(uuid))) {
			save();
		}
	}

	/** The account whose skin the preview shows when nothing is active — the first saved one. */
	public static AltAccount first() {
		List<AltAccount> list = store().accounts;
		return list.isEmpty() ? null : list.get(0);
	}
}
