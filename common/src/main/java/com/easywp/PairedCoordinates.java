package com.easywp;

/**
 * The 1:8 coordinate relationship between the Overworld and the Nether, on one horizontal axis.
 *
 * <p>Pure arithmetic, so it lives in {@code common/} with its own tests. The client uses it both to
 * place a waypoint's copy in the other dimension and to recognise a copy that already exists, and
 * the two must agree exactly: a copy recognised by a slightly different rule than the one that
 * placed it would let "duplicate" create a second one.
 */
public final class PairedCoordinates {
    private PairedCoordinates() {
    }

    /** An Overworld coordinate in the Nether: divided by 8, rounded ({@code -15} gives {@code -2}). */
    public static int toNether(int overworld) {
        return (int) Math.round(overworld / 8.0);
    }

    /** A Nether coordinate in the Overworld: multiplied by 8. */
    public static int toOverworld(int nether) {
        return nether * 8;
    }

    /**
     * Whether an Overworld waypoint at {@code (owX, owZ)} and a Nether one at {@code (netherX,
     * netherZ)} are copies of each other - one placed from the other by {@link #toNether} or
     * {@link #toOverworld}, in either direction.
     *
     * <p>One test covers both: a Nether copy of the Overworld point is {@code toNether} of it by
     * definition, and an Overworld copy of the Nether point is {@code nether * 8}, whose
     * {@code toNether} is exactly that Nether point again. The reverse test would not do: the
     * Overworld point {@code -15} has the Nether copy {@code -2}, but {@code -2 * 8} is {@code -16},
     * so comparing in Overworld coordinates would miss its own copy.
     */
    public static boolean areCopies(int owX, int owZ, int netherX, int netherZ) {
        return toNether(owX) == netherX && toNether(owZ) == netherZ;
    }
}
