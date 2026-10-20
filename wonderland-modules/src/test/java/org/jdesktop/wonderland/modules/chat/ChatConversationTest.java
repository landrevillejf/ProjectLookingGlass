/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (text-chat): the conversation model + message value.
 * Pins the faithful behaviours: non-blank sender/body, capacity trimming to the
 * newest lines, ordered listener notification, join-order participants, and the
 * ChatMessage value contract.
 */
package org.jdesktop.wonderland.modules.chat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatConversationTest {

    @Test
    void postingAppendsAndReturnsTheMessage() {
        ChatConversation conversation = new ChatConversation();
        ChatMessage message = conversation.post("alice", "hello");

        assertThat(message.getSender()).isEqualTo("alice");
        assertThat(message.getText()).isEqualTo("hello");
        assertThat(message.getTimestamp()).isPositive();
        assertThat(conversation.history()).containsExactly(message);
    }

    @Test
    void aBlankSenderOrBodyIsRejected() {
        ChatConversation conversation = new ChatConversation();

        assertThatThrownBy(() -> conversation.post("  ", "hi"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.post(null, "hi"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.post("alice", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.post("alice", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTranscriptIsBoundedToItsCapacity() {
        ChatConversation conversation = new ChatConversation(2);
        conversation.post("a", "one");
        conversation.post("a", "two");
        conversation.post("a", "three");

        List<ChatMessage> history = conversation.history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getText()).isEqualTo("two");
        assertThat(history.get(1).getText()).isEqualTo("three");
    }

    @Test
    void listenersAreNotifiedInOrderForEachPostedLine() {
        ChatConversation conversation = new ChatConversation();
        List<String> seen = new ArrayList<>();
        conversation.addListener(message -> seen.add(message.getText()));
        conversation.addListener(null); // ignored

        conversation.post("a", "one");
        conversation.post("b", "two");

        assertThat(seen).containsExactly("one", "two");
    }

    @Test
    void participantsJoinInOrderAndLeave() {
        ChatConversation conversation = new ChatConversation();
        conversation.join("alice");
        conversation.join("bob");
        conversation.join("alice"); // refresh, no duplicate

        assertThat(conversation.participants()).containsExactly("alice", "bob");
        conversation.leave("alice");
        assertThat(conversation.participants()).containsExactly("bob");
        conversation.leave("nobody"); // absent, ignored
        assertThat(conversation.participants()).containsExactly("bob");
    }

    @Test
    void aNonPositiveCapacityIsRejected() {
        assertThatThrownBy(() -> new ChatConversation(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatConversation(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joiningABlankNameIsRejected() {
        assertThatThrownBy(() -> new ChatConversation().join(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultConversationUsesTheScrollbackCeiling() {
        assertThat(ChatConversation.DEFAULT_CAPACITY).isEqualTo(200);
    }

    @Test
    void messageValueSemanticsAndRendering() {
        ChatMessage one = new ChatMessage("alice", "hi", 123L);
        ChatMessage same = new ChatMessage("alice", "hi", 123L);
        ChatMessage other = new ChatMessage("bob", "hi", 123L);
        ChatMessage laterStamp = new ChatMessage("alice", "hi", 999L);
        ChatMessage otherText = new ChatMessage("alice", "bye", 123L);

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(other);
        assertThat(one).isNotEqualTo(laterStamp);  // a different timestamp
        assertThat(one).isNotEqualTo(otherText);   // a different body
        assertThat(one).isNotEqualTo("not a message");
        assertThat(one).isEqualTo(one);
        assertThat(one.toString()).isEqualTo("alice: hi");
    }
}
