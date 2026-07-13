package unlucky.utility.client.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Chibi pixel-art player sprites — an exact clone of SkinSprite Studio's
 * renderer (sss.1m3.jp), recipe recovered with Lucien via calibration skins:
 * coordinate-encoded template skins went through the site and every output
 * pixel decoded to (face, source x, source y). Verified at mean error ~3/255
 * with zero alpha mismatches against the site's native exports.
 *
 * <p>The recovered recipe (facing right, standing, 24x33 core canvas):
 * <ul>
 *   <li>Each visible cube face maps to an axis-aligned rect (yaw-ortho
 *       projection): head front 8→16px wide + full right-side face compressed
 *       8→6px; hat the same one pixel larger all around (18x18 + 6x18).</li>
 *   <li>Torso front 8x12 → 10x10 with a 1px side sliver; arms hang 1px lower
 *       (shoulder droop): near arm = 3px side (cols 1..3 of the side face,
 *       1:1) + 3px front; far arm = 1px inner side + first 2 front cols
 *       (occluded by the turn). Legs 12→6 tall: near leg 4px side @1:1 +
 *       5px front; far leg 5px front only.</li>
 *   <li>Sampling is a box filter; overlays alpha-blend by coverage
 *       (base layers stamp where coverage ≥ 0.5).</li>
 *   <li>The signature pastel tone: every final pixel is desaturated 12%
 *       toward its Rec.601 luma (t = 0.120 — fit on calibration colors).</li>
 *   <li>Plus the 1px outline the site offers (their +1px exports grow the
 *       canvas by 2, same as ours: 26x35 total).</li>
 * </ul>
 *
 * <p>Pipeline per UUID, all async: disk cache
 * ({@code config/unlucky/sprites/<uuid>.png}, refreshed after a day) or
 * sessionserver → skin PNG download → compose on a worker → register as a
 * {@link DynamicTexture} on the client thread. {@link #get} returns null
 * until ready — callers fall back to {@link HeadRenderer}.
 */
public final class PlayerSprite {
	private static final float DESAT = 0.120f;
	private static final int OUTLINE_COLOR = 0xFF111111;
	private static final int CORE_W = 24;
	private static final int CORE_H = 33;

	/** Final canvas incl. the 1px outline ring. */
	public static final int WIDTH = CORE_W + 2;
	public static final int HEIGHT = CORE_H + 2;

	private static final Map<UUID, Identifier> SPRITES = new HashMap<>();
	private static final Set<UUID> PENDING = new HashSet<>();
	private static HttpClient http;

	private PlayerSprite() {
	}

	/** The sprite texture for {@code id}, or null while it cooks (fall back to a face). */
	public static Identifier get(UUID id) {
		Identifier ready = SPRITES.get(id);
		if (ready != null) {
			return ready;
		}
		if (PENDING.add(id)) {
			start(id);
		}
		return null;
	}

	/** Draw at {@code h} tall, aspect-correct; returns the drawn width. */
	public static int draw(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int h) {
		int w = h * WIDTH / HEIGHT;
		g.blit(RenderPipelines.GUI_TEXTURED, sprite, x, y, 0.0f, 0.0f, w, h, WIDTH, HEIGHT, WIDTH, HEIGHT, -1);
		return w;
	}

	private static Path cacheFile(UUID id) {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("unlucky/sprites/" + id.toString().replace("-", "") + ".png");
	}

	private static void start(UUID id) {
		Path cache = cacheFile(id);
		try {
			if (Files.isRegularFile(cache)
					&& Files.getLastModifiedTime(cache).toMillis() > System.currentTimeMillis() - 24 * 3600_000L) {
				byte[] bytes = Files.readAllBytes(cache);
				NativeImage cached = NativeImage.read(bytes);
				if (cached.getWidth() == WIDTH && cached.getHeight() == HEIGHT) {
					register(id, cached);
					return;
				}
				cached.close(); // stale format from an older recipe — recompose
			}
		} catch (Exception ignored) {
			// fall through to a fresh fetch
		}
		MinecraftServicesApi.fetchSkinOf(id,
				(url, slim) -> download(id, url, slim),
				message -> PENDING.remove(id)); // retry on a later get()
	}

	private static void download(UUID id, String url, boolean slim) {
		if (http == null) {
			http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
		http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenAccept(response -> {
					try (NativeImage skin = NativeImage.read(response.body())) {
						NativeImage sprite = compose(skin, slim);
						try {
							Files.createDirectories(cacheFile(id).getParent());
							sprite.writeToFile(cacheFile(id));
						} catch (Exception e) {
							UnluckyClientMod.LOGGER.warn("Sprite cache write failed", e);
						}
						Minecraft.getInstance().execute(() -> register(id, sprite));
					} catch (Exception e) {
						UnluckyClientMod.LOGGER.warn("Sprite compose failed for {}", id, e);
						PENDING.remove(id);
					}
				})
				.exceptionally(error -> {
					PENDING.remove(id);
					return null;
				});
	}

	private static void register(UUID id, NativeImage sprite) {
		Identifier texture = Identifier.fromNamespaceAndPath("unlucky",
				"sprites/" + id.toString().replace("-", ""));
		Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(texture::toString, sprite));
		SPRITES.put(id, texture);
	}

	// --- the extracted recipe ---

	/** One face placement: skin rect -> canvas rect. Legacy handled by caller. */
	private record Face(int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh) {
	}

	private static Face[] layout(boolean slim, boolean legacy) {
		int aw = slim ? 3 : 4;
		// legacy 64x32 skins: no left-limb textures (mirror right), no lower overlays
		int legLFrontX = legacy ? 4 : 20;
		int legLFrontY = legacy ? 20 : 52;
		int armLFrontX = legacy ? 44 : 36;
		int armLFrontY = legacy ? 20 : 52;
		return new Face[]{
				// legs (base then pants)
				new Face(0, 20, 4, 12, 6, 28, 4, 6),                       // legR side
				legacy ? null : new Face(0, 36, 4, 12, 6, 28, 4, 6),       // pantsR side
				new Face(4, 20, 4, 12, 10, 28, 5, 6),                      // legR front
				legacy ? null : new Face(4, 36, 4, 12, 10, 28, 5, 6),      // pantsR front
				new Face(legLFrontX, legLFrontY, 4, 12, 15, 28, 5, 6),     // legL front
				legacy ? null : new Face(4, 52, 4, 12, 15, 28, 5, 6),      // pantsL front
				// body: 1px side sliver + squished front, jacket over
				new Face(16, 20, 4, 12, 9, 18, 1, 10),                     // torso side
				legacy ? null : new Face(16, 36, 4, 12, 9, 18, 1, 10),     // jacket side
				new Face(20, 20, 8, 12, 10, 18, 10, 10),                   // torso front
				legacy ? null : new Face(20, 36, 8, 12, 10, 18, 10, 10),   // jacket front
				// near (right) arm: side cols 1..3 @1:1 + front, shoulder +1
				new Face(41, 20, 3, 12, 3, 19, 3, 9),                      // armR side
				legacy ? null : new Face(41, 36, 3, 12, 3, 19, 3, 9),      // sleeveR side
				new Face(44, 20, aw, 12, 6, 19, 3, 9),                     // armR front
				legacy ? null : new Face(44, 36, aw, 12, 6, 19, 3, 9),     // sleeveR front
				// far (left) arm: 1px inner side + first two front cols
				legacy ? null : new Face(36 + aw + 3, 52, 1, 12, 20, 19, 1, 9), // armL inner side
				legacy ? null : new Face(52 + aw + 3, 52, 1, 12, 20, 19, 1, 9), // sleeveL inner side
				new Face(armLFrontX, armLFrontY, 2, 12, 21, 19, 2, 9),     // armL front (2 cols)
				legacy ? null : new Face(52, 52, 2, 12, 21, 19, 2, 9),     // sleeveL front
				// head then hat (hat 1px overhang all around)
				new Face(0, 8, 8, 8, 2, 2, 6, 16),                         // head side
				new Face(8, 8, 8, 8, 8, 2, 16, 16),                        // head front
				new Face(32, 8, 8, 8, 1, 1, 6, 18),                        // hat side
				new Face(40, 8, 8, 8, 7, 1, 18, 18),                       // hat front
		};
	}

	/** Box-filtered, coverage-blended compose + desaturation + outline. */
	static NativeImage compose(NativeImage skin, boolean slim) {
		boolean legacy = skin.getHeight() == 32;
		float[][][] canvas = new float[HEIGHT][WIDTH][0];
		for (Face face : layout(slim, legacy)) {
			if (face == null) {
				continue;
			}
			for (int dy = 0; dy < face.dh; dy++) {
				double y0 = face.sy + dy * (double) face.sh / face.dh;
				double y1 = face.sy + (dy + 1) * (double) face.sh / face.dh;
				for (int dx = 0; dx < face.dw; dx++) {
					double x0 = face.sx + dx * (double) face.sw / face.dw;
					double x1 = face.sx + (dx + 1) * (double) face.sw / face.dw;
					double r = 0;
					double g = 0;
					double b = 0;
					double a = 0;
					double area = 0;
					for (int yy = (int) y0; yy < y1 - 1e-9; yy++) {
						double hgt = Math.min(y1, yy + 1) - Math.max(y0, yy);
						for (int xx = (int) x0; xx < x1 - 1e-9; xx++) {
							double wid = Math.min(x1, xx + 1) - Math.max(x0, xx);
							double weight = wid * hgt;
							int argb = skin.getPixel(Math.min(xx, skin.getWidth() - 1),
									Math.min(yy, skin.getHeight() - 1));
							if ((argb >>> 24) > 127) {
								r += ((argb >> 16) & 0xFF) * weight;
								g += ((argb >> 8) & 0xFF) * weight;
								b += (argb & 0xFF) * weight;
								a += weight;
							}
							area += weight;
						}
					}
					if (a <= 0) {
						continue;
					}
					float cov = (float) (a / area);
					float pr = (float) (r / a);
					float pg = (float) (g / a);
					float pb = (float) (b / a);
					float[] base = canvas[face.dy + dy][face.dx + dx];
					if (base.length == 0) {
						if (cov >= 0.5f) {
							canvas[face.dy + dy][face.dx + dx] = new float[]{pr, pg, pb};
						}
					} else {
						base[0] = pr * cov + base[0] * (1 - cov);
						base[1] = pg * cov + base[1] * (1 - cov);
						base[2] = pb * cov + base[2] * (1 - cov);
					}
				}
			}
		}
		NativeImage out = new NativeImage(WIDTH, HEIGHT, true);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				float[] v = canvas[y][x];
				if (v.length == 0) {
					continue;
				}
				float luma = 0.299f * v[0] + 0.587f * v[1] + 0.114f * v[2];
				int r = Math.round(v[0] + (luma - v[0]) * DESAT);
				int g = Math.round(v[1] + (luma - v[1]) * DESAT);
				int b = Math.round(v[2] + (luma - v[2]) * DESAT);
				out.setPixel(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
			}
		}
		outline(out);
		return out;
	}

	/** 1px black dilation around the silhouette. */
	private static void outline(NativeImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		boolean[] solid = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				solid[y * w + x] = (img.getPixel(x, y) >>> 24) > 40;
			}
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (solid[y * w + x]) {
					continue;
				}
				boolean edge = (x > 0 && solid[y * w + x - 1]) || (x < w - 1 && solid[y * w + x + 1])
						|| (y > 0 && solid[(y - 1) * w + x]) || (y < h - 1 && solid[(y + 1) * w + x]);
				if (edge) {
					img.setPixel(x, y, OUTLINE_COLOR);
				}
			}
		}
	}
}
