package unlucky.utility.client.module.modules.world;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.WorldScan;

/**
 * Reads nearby signs into your chat, once per sign.
 * Inspired by Stardust's ChatSigns.
 */
public class ChatSigns extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius in blocks", 32, 8, 64, 4));
	public final BooleanSetting backSide = add(new BooleanSetting("Back side", "Also read the back of signs", false));
	public final BooleanSetting coords = add(new BooleanSetting("Coords", "Include the sign position", true));

	private final Set<BlockPos> announced = new HashSet<>();
	private Level lastLevel;
	private int ticksUntilScan;

	public ChatSigns() {
		super("ChatSigns", "Reads nearby signs in chat", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		announced.clear();
		ticksUntilScan = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null) {
			return;
		}
		if (mc().level != lastLevel) {
			lastLevel = mc().level;
			announced.clear();
		}
		if (--ticksUntilScan > 0) {
			return;
		}
		ticksUntilScan = 10; // scan twice a second

		for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(range.get())) {
			if (!(blockEntity instanceof SignBlockEntity sign) || !announced.add(sign.getBlockPos())) {
				continue;
			}
			String front = joinLines(sign, true);
			String back = backSide.get() ? joinLines(sign, false) : "";
			if (front.isEmpty() && back.isEmpty()) {
				continue;
			}
			StringBuilder message = new StringBuilder("§7Sign");
			if (coords.get()) {
				BlockPos pos = sign.getBlockPos();
				message.append(" §8@ §7").append(pos.getX()).append(" ").append(pos.getY()).append(" ").append(pos.getZ());
			}
			message.append("§8:§r ");
			if (!front.isEmpty()) {
				message.append(front);
			}
			if (!back.isEmpty()) {
				message.append(front.isEmpty() ? "" : " §8| back:§r ").append(back);
			}
			ChatUtil.info(message.toString());
		}
	}

	private static String joinLines(SignBlockEntity sign, boolean front) {
		StringBuilder joined = new StringBuilder();
		for (Component line : sign.getText(front).getMessages(false)) {
			String text = line.getString().trim();
			if (!text.isEmpty()) {
				if (!joined.isEmpty()) {
					joined.append(" §8/§r ");
				}
				joined.append(text);
			}
		}
		return joined.toString();
	}
}
