/*
 * wonderland-modules M4 (text-chat): the headless conversation model.
 *
 * The transcript + participant roster the chat module owns, free of any Swing
 * or scene-graph dependency so it runs and is covered in a headless CI. The
 * 2006 module's conversation WINDOW is the deferred half (M6/M7).
 *
 * Faithful behaviours pinned here: a message needs a non-blank sender and
 * body; the transcript is bounded and drops its oldest line at capacity; posting
 * notifies registered listeners in order.
 */
package org.jdesktop.wonderland.modules.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** An ordered, bounded chat transcript with a participant roster. */
public final class ChatConversation {

    /** The default transcript bound, matching the 2006 chat scrollback ceiling. */
    public static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final Set<String> participants = new LinkedHashSet<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<Consumer<ChatMessage>> listeners = new ArrayList<>();

    /** A conversation with the default capacity. */
    public ChatConversation() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity maximum transcript length; must be positive
     */
    public ChatConversation(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Add (or refresh) a participant. */
    public void join(String name) {
        requireName(name);
        participants.add(name);
    }

    /** Remove a participant; absent names are ignored. */
    public void leave(String name) {
        participants.remove(name);
    }

    /** The participants, in join order. */
    public Set<String> participants() {
        return new LinkedHashSet<>(participants);
    }

    /**
     * Post a line.
     *
     * @param sender non-blank display name
     * @param text non-blank body
     * @return the immutable message appended
     * @throws IllegalArgumentException when either field is null or blank
     */
    public ChatMessage post(String sender, String text) {
        requireName(sender);
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("chat text must not be blank");
        }
        ChatMessage message = new ChatMessage(sender, text, System.currentTimeMillis());
        messages.add(message);
        while (messages.size() > capacity) {
            messages.remove(0);
        }
        for (Consumer<ChatMessage> listener : new ArrayList<>(listeners)) {
            listener.accept(message);
        }
        return message;
    }

    /** The transcript, oldest first, as a snapshot. */
    public List<ChatMessage> history() {
        return List.copyOf(messages);
    }

    /** Register a listener notified of each posted message. */
    public void addListener(Consumer<ChatMessage> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private static void requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("participant name must not be blank");
        }
    }
}
