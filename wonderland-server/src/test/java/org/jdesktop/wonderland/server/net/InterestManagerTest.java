/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the audience list, headless.
 *
 * Each assert is a statement about proximity and replacement: near hears,
 * far does not, an event's own footprint widens the reach, a moved watch
 * answers from its NEW place only, and a departed session is gone from the
 * next audience -- the ghost-recipient bugs are what this owner class exists
 * to prevent. The fan-out to real sockets is the router's, and its test is a
 * running server; the judgement of WHO is in the audience is all here.
 */
package org.jdesktop.wonderland.server.net;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterestManagerTest {

    private static final BigInteger ALICE = BigInteger.ONE;
    private static final BigInteger BOB = BigInteger.TWO;

    @Test
    void anEmptyWorldHasNoAudienceAndAnswersWithoutThrowing() {
        InterestManager interests = new InterestManager();

        assertThat(interests.size()).isZero();
        assertThat(interests.audienceFor(0, 0, 0, 100f)).isEmpty();
    }

    @Test
    void aNearSessionHearsAndAFarOneDoesNot() {
        InterestManager interests = new InterestManager();
        interests.watch(ALICE, 0, 0, 0, 10f);
        interests.watch(BOB, 500, 0, 0, 10f);

        List<BigInteger> audience = interests.audienceFor(2, 0, 0, 0f);

        assertThat(audience).containsExactly(ALICE);
    }

    @Test
    void theEventsOwnFootprintWidensTheReach() {
        // The union rule: a watch 12 m out with radius 10 does NOT hear a
        // point event, but the same event announcing radius 5 reaches it.
        InterestManager interests = new InterestManager();
        interests.watch(ALICE, 12, 0, 0, 10f);

        assertThat(interests.audienceFor(0, 0, 0, 0f)).isEmpty();
        assertThat(interests.audienceFor(0, 0, 0, 5f)).containsExactly(ALICE);
    }

    @Test
    void aMovedWatchAnswersFromItsNewPlaceOnly() {
        InterestManager interests = new InterestManager();
        interests.watch(ALICE, 0, 0, 0, 5f);
        interests.watch(ALICE, 200, 0, 0, 5f); // the avatar walked away

        assertThat(interests.size()).as("one session, one watch, replaced")
                .isEqualTo(1);
        assertThat(interests.audienceFor(0, 0, 0, 1f)).isEmpty();
        assertThat(interests.audienceFor(200, 0, 0, 1f)).containsExactly(ALICE);
    }

    @Test
    void aDepartedSessionLeavesTheNextAudience() {
        InterestManager interests = new InterestManager();
        interests.watch(ALICE, 0, 0, 0, 5f);
        interests.watch(BOB, 1, 0, 0, 5f);

        interests.forget(ALICE);
        interests.forget(ALICE); // a second goodbye is a no-op, like an unknown detach

        assertThat(interests.size()).isEqualTo(1);
        assertThat(interests.audienceFor(0, 0, 0, 1f)).containsExactly(BOB);
    }

    @Test
    void aSlippedRadiusIsClampedNotInvertedAndCoincidentStillHeard() {
        InterestManager interests = new InterestManager();
        interests.watch(ALICE, 3, 0, 0, -5f); // a negative radius is a caller's slip

        assertThat(interests.audienceFor(3, 0, 0, 0f))
                .as("radius 0 at the exact place still reaches distance 0")
                .containsExactly(ALICE);
        assertThat(interests.audienceFor(4, 0, 0, 0f)).isEmpty();
    }

    @Test
    void nanPlacesAreRefusedBecauseNaNComparisonsSilentlyExcludeEveryone() {
        InterestManager interests = new InterestManager();

        assertThatThrownBy(() -> interests.watch(ALICE, Float.NaN, 0, 0, 5f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interests.audienceFor(Float.NaN, 0, 0, 1f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interests.watch(null, 0, 0, 0, 5f))
                .isInstanceOf(NullPointerException.class);
    }
}
