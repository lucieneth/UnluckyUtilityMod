package unlucky.utility.client.mixin;

import java.util.List;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The chat history, and the call that rebuilds the display lines from it.
 *
 * <p>Both are private and both are needed by exactly one feature — BetterChat's duplicate
 * compacting, which has to <em>remove</em> an already-shown message rather than merely change
 * what the next one looks like. {@code allMessages} is the authoritative list;
 * {@code trimmedMessages} is derived from it by {@code refreshTrimmedMessages}, so dropping an
 * entry and asking for a refresh is the whole operation, and vanilla's own re-split (including
 * the chat-head indent) runs as part of it.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<GuiMessage> unlucky$allMessages();

	@Invoker("refreshTrimmedMessages")
	void unlucky$refreshTrimmedMessages();
}
