package unlucky.utility.client.module.modules.misc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatFont;
import unlucky.utility.client.util.ChatUtil;

/**
 * Preaches a random verse into chat on a timer — the other half of the OG anarchy
 * spam tradition, and the one people actually enjoyed.
 *
 * <p>Verses come from bible-api.com's {@code /data/{translation}/random} endpoint,
 * which answers {@code {translation: {...}, random_verse: {book, chapter, verse,
 * text}}}. Both translations offered here are public domain.
 *
 * <p>The fetch is off-thread and the send hops back via {@code Minecraft.execute} —
 * a blocking HTTP call on the client thread would freeze the game for as long as
 * the API took to answer. Only one request is ever in flight: if the API is being
 * slow, the timer skips rather than piling up requests that all land at once.
 */
public class BibleBot extends Module {
	private static final String API = "https://bible-api.com/data/";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public final ModeSetting translation = add(new ModeSetting("Translation",
			"WEB is the World English Bible, KJV the King James. Both are public domain.",
			"WEB", "WEB", "KJV"));
	public final ModeSetting font = add(new ModeSetting("Font",
			"Unicode letters to write it in. All of these render in vanilla chat.",
			"Normal", ChatFont.MODES)
			.withLabels(style -> ChatFont.apply(style, style))); // each style shown in itself
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Seconds between verses", 60, 10, 600, 5));
	public final BooleanSetting citation = add(new BooleanSetting("Citation",
			"End with the book, chapter and verse", true));

	private int ticks;
	private volatile boolean fetching;
	private boolean warned;

	public BibleBot() {
		super("BibleBot", "Sends a random Bible verse on a timer", Category.MISC);
	}

	@Override
	protected void onEnable() {
		ticks = 0;
		warned = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().player.connection == null) {
			return;
		}
		if (++ticks < delay.getInt() * 20) {
			return;
		}
		ticks = 0;
		if (!fetching) {
			fetch();
		}
	}

	private void fetch() {
		fetching = true;
		String url = API + translation.get().toLowerCase(Locale.ROOT) + "/random";
		new Thread(() -> {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url))
						.header("User-Agent", "UnluckyClient")
						.timeout(Duration.ofSeconds(15))
						.GET()
						.build();
				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() / 100 != 2) {
					throw new IOException("HTTP " + response.statusCode());
				}
				JsonObject verse = JsonParser.parseString(response.body()).getAsJsonObject()
						.getAsJsonObject("random_verse");
				String line = format(verse);
				// back on the client thread, and only if the module is still on —
				// a slow reply shouldn't speak after you turned it off
				Minecraft.getInstance().execute(() -> {
					if (isEnabled()) {
						ChatUtil.say(line);
					}
				});
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.warn("BibleBot verse fetch failed", e);
				Minecraft.getInstance().execute(this::reportOnce);
			} finally {
				fetching = false;
			}
		}, "unlucky-biblebot").start();
	}

	/** One complaint per enable — a dead connection shouldn't fill your own chat. */
	private void reportOnce() {
		if (!warned && isEnabled()) {
			warned = true;
			ChatUtil.info("§cBibleBot: couldn't reach bible-api.com — will keep trying quietly.");
		}
	}

	/**
	 * {@code verse text - Book chapter:verse}, styled and trimmed to the chat limit.
	 *
	 * <p>The citation is measured first and the verse trimmed around it, so a long
	 * passage loses its tail instead of its reference. Verse text from the API can
	 * carry newlines (poetry books especially), which chat won't take.
	 */
	private String format(JsonObject verse) {
		String text = verse.get("text").getAsString().replaceAll("\\s+", " ").trim();
		String reference = citation.get()
				? " - " + verse.get("book").getAsString() + " "
						+ verse.get("chapter").getAsInt() + ":" + verse.get("verse").getAsInt()
				: "";
		String styledReference = ChatFont.apply(reference, font.get());
		String styledText = ChatFont.apply(text, font.get());
		int room = ChatFont.MAX_CHAT - styledReference.length();
		if (styledText.length() > room) {
			styledText = ChatFont.fit(styledText, room - 3) + "...";
		}
		// belt and braces: a pathological book name can't push the line over the cap
		return ChatFont.fit(styledText + styledReference, ChatFont.MAX_CHAT);
	}
}
