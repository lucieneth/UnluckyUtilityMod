package unlucky.utility.client.module.modules.player;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Reads books held by other players, in item frames, or dropped on the ground
 * into your chat. Inspired by Stardust's PagePirate.
 */
public class PagePirate extends Module {
	public final BooleanSetting players = add(new BooleanSetting("Players", "Books in other players' hands", true));
	public final BooleanSetting itemFrames = add(new BooleanSetting("Item frames", "Books in item frames", true));
	public final BooleanSetting groundItems = add(new BooleanSetting("Ground items", "Dropped books", true));
	public final NumberSetting maxPages = add(new NumberSetting("Max pages", "Pages printed per book", 5, 1, 20, 1));

	private final Set<Integer> seenBooks = new HashSet<>();
	private int ticksUntilScan;

	public PagePirate() {
		super("PagePirate", "Reads books around you into chat", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		seenBooks.clear();
		ticksUntilScan = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null || --ticksUntilScan > 0) {
			return;
		}
		ticksUntilScan = 20;

		if (players.get()) {
			for (Player player : mc().level.players()) {
				if (player != mc().player) {
					pirate(player.getMainHandItem(), "held by " + player.getName().getString());
					pirate(player.getOffhandItem(), "held by " + player.getName().getString());
				}
			}
		}
		if (itemFrames.get() || groundItems.get()) {
			for (Entity entity : mc().level.entitiesForRendering()) {
				if (itemFrames.get() && entity instanceof ItemFrame frame) {
					pirate(frame.getItem(), "in item frame at " + entity.blockPosition().toShortString());
				} else if (groundItems.get() && entity instanceof ItemEntity item) {
					pirate(item.getItem(), "on the ground at " + entity.blockPosition().toShortString());
				}
			}
		}
	}

	private void pirate(ItemStack stack, String location) {
		if (stack.isEmpty()) {
			return;
		}
		WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
		WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if (written == null && (writable == null || writable.pages().isEmpty())) {
			return;
		}

		int hash = written != null ? written.hashCode() : writable.hashCode();
		if (!seenBooks.add(hash)) {
			return;
		}

		if (written != null) {
			ChatUtil.info("§7Book §f" + written.title().raw() + "§7 by §f" + written.author() + "§7 (" + location + "):");
			int page = 1;
			for (Filterable<Component> content : written.pages()) {
				if (page > maxPages.getInt()) {
					ChatUtil.info("§8... " + (written.pages().size() - maxPages.getInt()) + " more pages");
					break;
				}
				ChatUtil.info(Component.literal("§8p" + page + ":§r ").append(content.raw()));
				page++;
			}
		} else {
			ChatUtil.info("§7Writable book (" + location + "):");
			int page = 1;
			for (Filterable<String> content : writable.pages()) {
				if (page > maxPages.getInt()) {
					ChatUtil.info("§8... " + (writable.pages().size() - maxPages.getInt()) + " more pages");
					break;
				}
				ChatUtil.info("§8p" + page + ":§r " + content.raw());
				page++;
			}
		}
	}
}
