package com.easywp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WelcomeVersionTest {

    @Test
    void dueWhenNeverShownOrShownForAnotherVersion() {
        assertTrue(WelcomeVersion.isNew(null, "1.4.0-alpha.7"));
        assertTrue(WelcomeVersion.isNew("1.4.0-alpha.6", "1.4.0-alpha.7"));
        assertTrue(WelcomeVersion.isNew("1.4.0-beta.1", "1.4.0"));
        assertFalse(WelcomeVersion.isNew("1.4.0-alpha.7", "1.4.0-alpha.7"));
        assertFalse(WelcomeVersion.isNew("1.4.0", null));
    }

    @Test
    void releaseDropsPreReleaseAndBuildParts() {
        assertEquals("1.4.0", WelcomeVersion.release("1.4.0-alpha.7"));
        assertEquals("1.4.0", WelcomeVersion.release("1.4.0+26.1-26.2"));
        assertEquals("1.4.0", WelcomeVersion.release("1.4.0-beta.1+26.3"));
        assertEquals("1.4.0", WelcomeVersion.release("1.4.0"));
        assertEquals("", WelcomeVersion.release(null));
    }
}
