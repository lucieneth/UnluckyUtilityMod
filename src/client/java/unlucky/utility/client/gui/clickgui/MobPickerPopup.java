package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Centered popup for an EntityListSetting: All/None buttons on top, then a
 * scrollable list of mobs with a small live render and a checkbox each.
 * The catalog (one never-ticked display entity per type) is built once on
 * open and dropped on close; render states are extracted only for the rows
 * currently on screen — the same path the inventory player preview uses.
 */
public final class MobPickerPopup {
	private static final int WIDTH = 210;
	private static final int HEIGHT = 220;
	private static final int ROW = 20;
	private static final int HEADER = 34;
	private static final Quaternionf FLIP = new Quaternionf().rotateZ((float) Math.PI);

	private record Entry(EntityType<?> type, Mob mob, String name) {
	}

	private static EntityListSetting target;
	private static List<Entry> entries = List.of();
	private static int scroll;
	// draggable position; MIN_VALUE = not placed yet, centered on first render
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static int dragOffX;
	private static int dragOffY;

	private MobPickerPopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(EntityListSetting setting, boolean hostile) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		target = setting;
		scroll = 0;
		List<Entry> built = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Entity created;
			try {
				created = type.create(mc.level, EntitySpawnReason.LOAD);
			} catch (Exception e) {
				continue; // some types can't exist client-side; skip them
			}
			// hostile = implements Enemy (covers ghasts, slimes, the dragon...)
			if (!(created instanceof Mob mob) || created instanceof Enemy != hostile) {
				continue;
			}
			// display-only entities never join the level, so no ID gets assigned —
			// but render state extraction reads getId(); hand out fake negative ones
			mob.setId(-1000 - built.size());
			built.add(new Entry(type, mob, type.getDescription().getString()));
		}
		built.sort(Comparator.comparing(Entry::name));
		entries = built;
	}

	public static void close() {
		target = null;
		entries = List.of();
	}

	public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (target == null) {
			return;
		}
		if (popX == Integer.MIN_VALUE) {
			popX = (g.guiWidth() - WIDTH) / 2;
			popY = (g.guiHeight() - HEIGHT) / 2;
		}
		int x = popX;
		int y = popY;

		Render2D.rect(g, x - 1, y - 1, WIDTH + 2, HEIGHT + 2, Theme.borderDark);
		Render2D.rect(g, x, y, WIDTH, HEIGHT, Theme.window);
		g.outline(x, y, WIDTH, HEIGHT, Theme.border);
		Render2D.textNoShadow(g, target.getName(), x + 6, y + 5, Theme.text);

		// All / None buttons
		String[] labels = {"All", "None"};
		int buttonWidth = (WIDTH - 18) / 2;
		for (int i = 0; i < 2; i++) {
			int bx = x + 6 + i * (buttonWidth + 6);
			int by = y + 16;
			boolean hover = Render2D.hovered(mouseX, mouseY, bx, by, buttonWidth, 13);
			Render2D.rect(g, bx - 1, by - 1, buttonWidth + 2, 15, Theme.borderDark);
			Render2D.rect(g, bx, by, buttonWidth, 13, hover ? Theme.panel : Theme.surface);
			Render2D.textNoShadow(g, labels[i], bx + (buttonWidth - Render2D.width(labels[i])) / 2, by + 3,
					hover ? Theme.text : Theme.textDim);
		}

		// mob list
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		g.enableScissor(x, listTop, x + WIDTH, listTop + listHeight);
		int rowY = listTop - scroll;
		for (Entry entry : entries) {
			if (rowY + ROW >= listTop && rowY <= listTop + listHeight) {
				boolean selected = target.allows(entry.type());
				boolean hover = Render2D.hovered(mouseX, mouseY, x + 2, rowY, WIDTH - 8, ROW);
				if (hover) {
					Render2D.rect(g, x + 2, rowY, WIDTH - 8, ROW, 0x18FFFFFF);
				}
				drawMob(g, entry.mob(), x + 4, rowY + 1, x + 22, rowY + ROW - 1);
				Render2D.textNoShadow(g, entry.name(), x + 27, rowY + 6,
						selected ? Theme.text : Theme.textDim);
				int boxX = x + WIDTH - 18;
				Render2D.rect(g, boxX - 1, rowY + 4, 11, 11, Theme.borderDark);
				Render2D.rect(g, boxX, rowY + 5, 9, 9,
						selected ? Theme.accent1 : ColorUtil.withAlpha(Theme.textDim, 70));
			}
			rowY += ROW;
		}
		g.disableScissor();

		// scrollbar
		int contentHeight = entries.size() * ROW;
		if (contentHeight > listHeight) {
			int barHeight = Math.max(listHeight * listHeight / contentHeight, 10);
			int barY = listTop + (listHeight - barHeight) * scroll / (contentHeight - listHeight);
			Render2D.rect(g, x + WIDTH - 4, listTop, 2, listHeight, Theme.surface);
			Render2D.verticalGradient(g, x + WIDTH - 4, barY, 2, barHeight, Theme.accent1, Theme.accent2);
		}
	}

	/** Submits the mob into the given box, angled slightly (shared HudEntity path). */
	private static void drawMob(GuiGraphicsExtractor g, Mob mob, int x0, int y0, int x1, int y1) {
		unlucky.utility.client.util.HudEntity.draw(g, mob, x0, y0, x1, y1, 25.0f, 0.0f, 0.0f);
	}

	/** Consumes every click while open; clicking outside the panel closes it. */
	public static boolean mouseClicked(double mouseX, double mouseY, int button, int guiWidth, int guiHeight) {
		if (target == null) {
			return false;
		}
		int x = popX;
		int y = popY;
		if (!Render2D.hovered(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
			close();
			return true;
		}
		if (button != 0) {
			return true;
		}
		// drag by the title strip (above the All/None buttons)
		if (mouseY < y + 15) {
			draggingPopup = true;
			dragOffX = (int) mouseX - x;
			dragOffY = (int) mouseY - y;
			return true;
		}

		// All / None
		int buttonWidth = (WIDTH - 18) / 2;
		for (int i = 0; i < 2; i++) {
			int bx = x + 6 + i * (buttonWidth + 6);
			if (Render2D.hovered(mouseX, mouseY, bx, y + 16, buttonWidth, 13)) {
				if (i == 0) {
					target.allowAll();
				} else {
					var all = new TreeSet<String>();
					for (Entry entry : entries) {
						all.add(BuiltInRegistries.ENTITY_TYPE.getKey(entry.type()).toString());
					}
					target.setAll(all);
				}
				return true;
			}
		}

		// rows
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		if (mouseY >= listTop && mouseY < listTop + listHeight) {
			int index = ((int) mouseY - listTop + scroll) / ROW;
			if (index >= 0 && index < entries.size()) {
				target.toggle(entries.get(index).type());
			}
		}
		return true;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int guiWidth, int guiHeight) {
		if (!draggingPopup) {
			return false;
		}
		popX = Math.clamp((int) mouseX - dragOffX, 0, Math.max(guiWidth - WIDTH, 0));
		popY = Math.clamp((int) mouseY - dragOffY, 0, Math.max(guiHeight - HEIGHT, 0));
		return true;
	}

	public static void mouseReleased() {
		draggingPopup = false;
	}

	public static boolean mouseScrolled(double scrollY) {
		if (target == null) {
			return false;
		}
		int listHeight = HEIGHT - HEADER - 4;
		int max = Math.max(entries.size() * ROW - listHeight, 0);
		scroll = Math.clamp(scroll - (int) (scrollY * 20), 0, max);
		return true;
	}
}
