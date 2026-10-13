/*
 * wonderland-modules M4 (user-list): the present-user roster and its value type.
 * Pins add/refresh by id (a repeat id updates the name but fires no second join),
 * remove semantics, name-sorted iteration, presence listeners, and the PresentUser
 * identity/value contract.
 */
package org.jdesktop.wonderland.modules.userlist;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserListTest {

    private static final class Recorder implements UserList.PresenceListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void userJoined(PresentUser user) {
            events.add("join:" + user.getId());
        }

        @Override
        public void userLeft(PresentUser user) {
            events.add("left:" + user.getId());
        }
    }

    @Test
    void addThenRemoveTracksPresenceAndFiresListeners() {
        UserList list = new UserList();
        Recorder recorder = new Recorder();
        list.addListener(recorder);
        list.addListener(null); // ignored

        list.add(new PresentUser("1", "Carol"));
        list.add(new PresentUser("2", "Bob"));
        list.remove("1");
        list.remove("absent"); // no-op

        assertThat(recorder.events).containsExactly("join:1", "join:2", "left:1");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.find("1")).isNull();
        assertThat(list.find("2")).isNotNull();
    }

    @Test
    void refreshingAnExistingIdUpdatesTheNameWithoutASecondJoin() {
        UserList list = new UserList();
        Recorder recorder = new Recorder();
        list.addListener(recorder);

        list.add(new PresentUser("1", "Carol"));
        list.add(new PresentUser("1", "Carol renamed"));

        assertThat(recorder.events).containsExactly("join:1");
        assertThat(list.find("1").getName()).isEqualTo("Carol renamed");
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    void iterationIsSortedByDisplayNameIgnoringCase() {
        UserList list = new UserList();
        list.add(new PresentUser("3", "carol"));
        list.add(new PresentUser("1", "Alice"));
        list.add(new PresentUser("2", "bob"));

        assertThat(list.users())
                .extracting(PresentUser::getName)
                .containsExactly("Alice", "bob", "carol");
    }

    @Test
    void removeReturnsTheRemovedUser() {
        UserList list = new UserList();
        PresentUser user = new PresentUser("9", "Nine");
        list.add(user);
        assertThat(list.remove("9")).isSameAs(user);
        assertThat(list.remove("9")).isNull();
    }

    @Test
    void aPresentUserNeedsBothIdAndName() {
        assertThatThrownBy(() -> new PresentUser(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PresentUser("1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presentUserIdentityIsItsIdAndRendersNameId() {
        PresentUser one = new PresentUser("1", "Alice");
        PresentUser sameIdOtherName = new PresentUser("1", "Alicia");
        PresentUser different = new PresentUser("2", "Alice");

        assertThat(one).isEqualTo(sameIdOtherName).hasSameHashCodeAs(sameIdOtherName);
        assertThat(one).isNotEqualTo(different);
        assertThat(one).isNotEqualTo("not a user");
        assertThat(one).isEqualTo(one);
        assertThat(one.toString()).isEqualTo("Alice (1)");
    }
}
