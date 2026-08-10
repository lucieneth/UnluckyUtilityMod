package unlucky.utility.client.util;

/**
 * Duck interface stitched onto the {@code GuiMessage} record by {@code GuiMessageMixin} —
 * carries BetterChat's normalised duplicate key and repeat count.
 *
 * <p>Sibling to {@link GuiMessageSender}, and here for the same reason: the answer has to
 * survive on the message itself. Recomputing the key from a displayed message would mean
 * parsing our own timestamp and {@code ×N} suffix back off text we had just written, and a
 * message that happens to end in "×3" on its own would then compare equal to one that
 * repeated three times.
 */
public interface ChatMessageKey {
	/** The comparison key, or null for a message BetterChat never saw. */
	String unlucky$chatKey();

	void unlucky$setChatKey(String key);

	/** How many times this line has arrived. 1 for the first. */
	int unlucky$chatCount();

	void unlucky$setChatCount(int count);
}
