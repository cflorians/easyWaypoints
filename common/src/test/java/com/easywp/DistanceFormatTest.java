package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistanceFormatTest {

    @Test
    void belowAKilometreStaysInMetres() {
        assertEquals("0m", DistanceFormat.format(0, true));
        assertEquals("37m", DistanceFormat.format(37, true));
        assertEquals("999m", DistanceFormat.format(999, true));
    }

    @Test
    void fromAKilometreOnShowsOneDecimal() {
        assertEquals("1.0km", DistanceFormat.format(1000, true));
        assertEquals("1.2km", DistanceFormat.format(1234, true));
        assertEquals("1.3km", DistanceFormat.format(1300, true));
        assertEquals("12.3km", DistanceFormat.format(12345, true));
        assertEquals("1000.0km", DistanceFormat.format(1_000_000, true));
    }

    @Test
    void kilometresAreTruncatedLikeTheMetres() {
        // 1999 m is not yet 2 km, the same way 999.9 blocks reads 999m.
        assertEquals("1.9km", DistanceFormat.format(1999, true));
        assertEquals("1.2km", DistanceFormat.format(1299, true));
    }

    @Test
    void metresOnlyIgnoresTheThreshold() {
        assertEquals("1234m", DistanceFormat.format(1234, false));
        assertEquals("1000000m", DistanceFormat.format(1_000_000, false));
    }

    @Test
    void theDecimalSeparatorIsAPointInEveryLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.2km", DistanceFormat.format(1234, true));
            Locale.setDefault(Locale.forLanguageTag("es-ES"));
            assertEquals("1.2km", DistanceFormat.format(1234, true));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void theCacheKeyChangesExactlyWhenTheTextDoes() {
        // Over both modes at once: one key must never stand for two different texts (or the
        // label would keep a stale text), and one text never needs two keys within a mode run.
        Map<Integer, String> textForKey = new HashMap<>();
        for (boolean useKm : new boolean[] {false, true}) {
            for (int m = 0; m <= 30_000; m++) {
                String text = DistanceFormat.format(m, useKm);
                String seen = textForKey.putIfAbsent(DistanceFormat.cacheKey(m, useKm), text);
                if (seen != null) {
                    assertEquals(seen, text, "key " + DistanceFormat.cacheKey(m, useKm) + " at " + m + "m");
                }
                if (m > 0) {
                    boolean sameText = text.equals(DistanceFormat.format(m - 1, useKm));
                    boolean sameKey = DistanceFormat.cacheKey(m, useKm) == DistanceFormat.cacheKey(m - 1, useKm);
                    assertEquals(sameText, sameKey, "between " + (m - 1) + " and " + m + "m, km=" + useKm);
                }
            }
        }
    }
}
