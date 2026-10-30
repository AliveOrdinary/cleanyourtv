package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(HomeKeyService.stillArmed(SOON, SOON + HomeKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(HomeKeyService.stillArmed(SOON, 0));
    }

    @Test public void customHomeSucceedsWhenTheStarterDoesNotThrow() {
        assertTrue(HomeKeyService.tryCustomHome(() -> { }));
    }

    @Test public void customHomeFallsThroughWhenTheTargetCannotBeFound() {
        assertFalse(HomeKeyService.tryCustomHome(() -> { throw new ActivityNotFoundException(); }));
    }

    @Test public void customHomeFallsThroughWhenTheTargetIsNotExportedOrPermissionProtected() {
        assertFalse(HomeKeyService.tryCustomHome(() -> { throw new SecurityException("not exported"); }));
    }

    @Test(expected = IllegalStateException.class)
    public void customHomeDoesNotSwallowAnUnrelatedRuntimeException() {
        HomeKeyService.tryCustomHome(() -> { throw new IllegalStateException("not ours to catch"); });
    }

    /** Feeds `times` (elapsedRealtime, ms) through loopingOnCustomHome as openHome() does: next only advances
     *  when an attempt is actually recorded, mirroring the production caller. Returns the last call's result. */
    private static boolean attempt(long[] ring, long... times) {
        int next = 0;
        boolean looping = false;
        for (long now : times) {
            looping = HomeKeyService.loopingOnCustomHome(ring, next, now);
            if (!looping) next = (next + 1) % ring.length;
        }
        return looping;
    }

    @Test public void theLoopTripsTheGuard() {
        long[] ring = HomeKeyService.freshGuardRing();
        // Three attempts 150 ms apart (the measured SetupFileOpenActivity cadence) all land inside the window;
        // a fourth right after is the one the guard catches.
        assertTrue(attempt(ring, 1_000, 1_150, 1_300, 1_450));
    }

    @Test public void normalRepeatedHomePressesDoNotTripTheGuard() {
        long[] ring = HomeKeyService.freshGuardRing();
        // A press every second, well under CUSTOM_HOME_GUARD_WINDOW_MS's 3-in-2s rate, for twice as many
        // attempts as the guard remembers - it never sees CUSTOM_HOME_GUARD_COUNT of them close together.
        assertFalse(attempt(ring, 0, 1_000, 2_000, 3_000, 4_000, 5_000));
    }

    @Test public void theWindowAgesOutAndTheTargetIsTriedAgain() {
        long[] ring = HomeKeyService.freshGuardRing();
        int next = 0;
        for (long now : new long[] {1_000, 1_150, 1_300}) {
            assertFalse(HomeKeyService.loopingOnCustomHome(ring, next, now));
            next = (next + 1) % ring.length;
        }
        assertTrue(HomeKeyService.loopingOnCustomHome(ring, next, 1_450)); // tripped, not recorded
        // well past CUSTOM_HOME_GUARD_WINDOW_MS since the oldest (1_000) ages it out, with no attempt in between
        long later = 1_000 + HomeKeyService.CUSTOM_HOME_GUARD_WINDOW_MS + 1;
        assertFalse(HomeKeyService.loopingOnCustomHome(ring, next, later));
    }

    @Test public void aHomeShapedTargetIsAccepted() {
        assertTrue(HomeKeyService.answersHome("com.klevico.monet.HomeActivity",
                Arrays.asList("com.klevico.monet.HomeActivity")));
    }

    @Test public void aNonHomeTargetIsRejected() {
        assertFalse(HomeKeyService.answersHome("com.klevico.monet.SetupFileOpenActivity",
                Arrays.asList("com.klevico.monet.HomeActivity")));
        assertFalse(HomeKeyService.answersHome("com.klevico.monet.SetupFileOpenActivity", Collections.emptyList()));
    }

    /** Mirrors openHome()'s guard bookkeeping call by call, composed from the two pure methods it actually
     *  calls: countsTowardCustomHomeGuard decides whether this attempt counts at all, and only a counted
     *  attempt reaches loopingOnCustomHome (and only advances next when that does not block it) - exactly as
     *  the production method does, so a test reproducing a sequence of real openHome() calls (a user's key
     *  presses, or a target's own self-retriggering window-watch loop) gets the same answer production would. */
    private static final class Guard {
        final long[] ring = HomeKeyService.freshGuardRing();
        int next;
        long lastHomeKeyAt = Long.MIN_VALUE / 2;

        /** One call to openHome(fromKeyPress) at elapsedRealtime now; true if the guard blocked it. */
        boolean call(boolean fromKeyPress, long now) {
            if (fromKeyPress) lastHomeKeyAt = now;
            if (!HomeKeyService.countsTowardCustomHomeGuard(fromKeyPress, lastHomeKeyAt, now)) return false;
            boolean blocked = HomeKeyService.loopingOnCustomHome(ring, next, now);
            if (!blocked) next = (next + 1) % ring.length;
            return blocked;
        }
    }

    @Test public void aDoublePressNeverTripsTheGuard() {
        Guard guard = new Guard();
        // Each press reaches openHome() twice: the window-watch almost at once (the same press's un-consumed
        // key opened launcherx, which this sees), then redirectHomeKey()'s own delayed call ~250 ms later.
        assertFalse(guard.call(false, 1_000));  // window-watch, press 1
        assertFalse(guard.call(true, 1_250));   // key path, press 1
        assertFalse(guard.call(false, 1_600));  // window-watch, press 2
        assertFalse(guard.call(true, 1_850));   // key path, press 2
    }

    @Test public void rapidUserPressesNeverTripTheGuard() {
        Guard guard = new Guard();
        long now = 1_000;
        // Six presses ~150 ms apart (the cadence a real remote reached that a chained `input keyevent` could
        // not - adb's own per-call overhead kept consecutive presses further apart than this).
        for (int i = 0; i < 6; i++) {
            assertFalse(guard.call(false, now));
            assertFalse(guard.call(true, now + 100));
            now += 150;
        }
    }

    @Test public void aSelfRetriggeringLoopWithNoKeyPressesStillTripsTheGuard() {
        Guard guard = new Guard();
        // No key press at all, as the real SetupFileOpenActivity loop has none: every attempt counts, so
        // CUSTOM_HOME_GUARD_COUNT of them (150 ms apart, the measured cadence) still trip it on the next one.
        assertFalse(guard.call(false, 1_000));
        assertFalse(guard.call(false, 1_150));
        assertFalse(guard.call(false, 1_300));
        assertTrue(guard.call(false, 1_450));
    }
}
