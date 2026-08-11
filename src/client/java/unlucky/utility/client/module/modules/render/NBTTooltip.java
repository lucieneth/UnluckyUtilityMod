package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Displays and copies the raw data-component patch behind an item stack. */
public class NBTTooltip extends Module {
	public final NumberSetting indentation = add(new NumberSetting("Indentation",
			"Spaces used for each nested component level", 2, 0, 4, 1));
	public final BooleanSetting colors = add(new BooleanSetting("Colors",
			"Color keys, strings, numbers, and brackets", true));
	public final BooleanSetting onlyOnKey = add(new BooleanSetting("Only on key",
			"Only expand raw component data while the display key is held", true));
	public final KeybindSetting displayKey = add(new KeybindSetting("Display key",
			"Hold this key while hovering an item", GLFW.GLFW_KEY_LEFT_CONTROL), onlyOnKey::get);
	public final KeybindSetting copyKey = add(new KeybindSetting("Copy key",
			"Press while the raw tooltip is open to copy uncolored SNBT", GLFW.GLFW_KEY_C));
	public final BooleanSetting requireControlToCopy = add(new BooleanSetting("Ctrl for copy",
			"Require either Control key together with the copy key", true));
	public final NumberSetting maxLines = add(new NumberSetting("Maximum lines",
			"Caps extremely large tooltips; copying still includes the full data", 256, 16, 2048, 16));

	private ItemStack cachedStack;
	private int cachedCount;
	private int cachedIndent;
	private boolean cachedColors;
	private int cachedMaxLines;
	private List<Component> cachedLines = List.of();
	private String cachedSnbt = "";
	private boolean copyWasDown;

	public NBTTooltip() {
		super("NBTTooltip", "Shows raw item data components and copies them from tooltips", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected boolean hiddenByDefault() {
		return true;
	}

	public List<Component> lines(ItemStack stack) {
		if (!displayHeld()) {
			copyWasDown = false;
			return List.of();
		}
		if (stack != cachedStack || stack.getCount() != cachedCount || indentation.getInt() != cachedIndent
				|| colors.get() != cachedColors || maxLines.getInt() != cachedMaxLines) {
			rebuild(stack);
		}
		copyIfPressed();
		return cachedLines;
	}

	private void rebuild(ItemStack stack) {
		cachedStack = stack;
		cachedCount = stack.getCount();
		cachedIndent = indentation.getInt();
		cachedColors = colors.get();
		cachedMaxLines = maxLines.getInt();
		cachedLines = List.of();
		cachedSnbt = "";
		if (mc().level == null) {
			return;
		}
		Tag encoded;
		try {
			encoded = DataComponentPatch.CODEC.encodeStart(
					mc().level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
					stack.getComponentsPatch()).getOrThrow();
		} catch (RuntimeException error) {
			cachedLines = List.of(Component.literal("NBT encode failed: " + error.getMessage())
					.withStyle(ChatFormatting.RED));
			return;
		}
		cachedSnbt = encoded.toString();
		List<Component> out = new ArrayList<>();
		out.add(Component.literal("Data components").withStyle(ChatFormatting.DARK_GRAY));
		append(out, null, encoded, 0);
		if (out.size() > cachedMaxLines) {
			int hidden = out.size() - cachedMaxLines;
			out = new ArrayList<>(out.subList(0, cachedMaxLines));
			out.add(Component.literal("… " + hidden + " more lines (copy key gets everything)")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		cachedLines = List.copyOf(out);
	}

	private void append(List<Component> out, String key, Tag tag, int depth) {
		String pad = " ".repeat(Math.max(0, depth * cachedIndent));
		MutableComponent prefix = Component.literal(pad);
		if (key != null) {
			prefix = prefix.append(colored(key, ChatFormatting.AQUA)).append(colored(": ", ChatFormatting.GRAY));
		}
		if (tag instanceof CompoundTag compound) {
			out.add(prefix.append(colored("{", ChatFormatting.WHITE)));
			for (String child : compound.keySet()) {
				Tag value = compound.get(child);
				if (value != null) append(out, child, value, depth + 1);
			}
			out.add(Component.literal(pad).append(colored("}", ChatFormatting.WHITE)));
		} else if (tag instanceof ListTag list) {
			out.add(prefix.append(colored("[", ChatFormatting.WHITE)));
			for (Tag value : list) append(out, null, value, depth + 1);
			out.add(Component.literal(pad).append(colored("]", ChatFormatting.WHITE)));
		} else {
			out.add(prefix.append(value(tag)));
		}
	}

	private Component value(Tag tag) {
		ChatFormatting color = tag instanceof StringTag ? ChatFormatting.GOLD
				: tag instanceof NumericTag ? ChatFormatting.GREEN
				: tag instanceof ByteArrayTag || tag instanceof IntArrayTag || tag instanceof LongArrayTag
						? ChatFormatting.BLUE : ChatFormatting.WHITE;
		return colored(tag.toString(), color);
	}

	private Component colored(String text, ChatFormatting color) {
		Component component = Component.literal(text);
		return cachedColors ? component.copy().withStyle(color) : component;
	}

	private boolean displayHeld() {
		return !onlyOnKey.get() || (displayKey.isBound()
				&& InputConstants.isKeyDown(mc().getWindow(), displayKey.get()));
	}

	private void copyIfPressed() {
		boolean control = InputConstants.isKeyDown(mc().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputConstants.isKeyDown(mc().getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
		boolean down = copyKey.isBound() && InputConstants.isKeyDown(mc().getWindow(), copyKey.get())
				&& (!requireControlToCopy.get() || control);
		if (down && !copyWasDown && !cachedSnbt.isEmpty()) {
			mc().keyboardHandler.setClipboard(cachedSnbt);
		}
		copyWasDown = down;
	}

	@Override
	protected void onDisable() {
		cachedStack = null;
		cachedLines = List.of();
		copyWasDown = false;
	}
}
