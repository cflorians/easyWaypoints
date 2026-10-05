package com.easywp.client;

import com.easywp.PairedCoordinates;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class Waypoint {
    private String name;
    private BlockPos pos;
    private int color;
    private String dimension;
    private boolean visible = true;
    private boolean focused = false;
    private boolean forceVisible = false;
    private boolean death = false;
    private long createdAtMillis = 0L;
    // Set only by setter, like forceVisible: Fast Ping (ModKeyBindings) is the only creation path,
    // which builds a plain Waypoint and flags it after - adding this to the canonical constructor
    // below would mean touching every call site for a flag almost none of them ever need.
    private boolean fastPing = false;
    // Set only by setter too: marked on the create screen's "More options" page (and loaded from
    // disk), never part of creating a waypoint.
    private boolean favorite = false;
    // Set only by setter too, like favorite: the groups this waypoint belongs to, by name (see
    // WaypointGroups). Usually empty; never null.
    private List<String> groups = new ArrayList<>();
    // Never saved: deleted from the list and waiting out the seconds in which that can be undone
    // (see WaypointStore.markForRemoval). Gone from the world and from the file already, still a
    // row in the list.
    private boolean pendingRemoval = false;

    // Render-side cache of the on-screen label built for this waypoint (see WaypointRenderer).
    // Lives here instead of a separate map because the renderer already reads and writes waypoint
    // state directly with no store abstraction in between - this just rides along with it, and
    // needs no cleanup when a waypoint is deleted since it goes away with the object.
    private String cachedLabelSourceName;
    private boolean cachedLabelUppercase;
    private boolean cachedLabelShowDistance;
    /** {@code DistanceFormat.cacheKey} of the distance shown, not the distance itself: in
     *  kilometres the text only changes every 100 blocks, and so does this. */
    private int cachedLabelDistanceKey;
    private String cachedLabelText;
    private float cachedLabelWidth;

    public Waypoint(String name, BlockPos pos) {
        this(name, pos, 0xFF00FF00);
    }

    public Waypoint(String name, BlockPos pos, int color) {
        this(name, pos, color, DimensionNames.OVERWORLD);
    }

    /**
     * Every shorter constructor ends here, and each one is used only to create a waypoint that did
     * not exist before - the create screen, Fast Ping, a Xaero import, a duplicate - so it is
     * stamped with the current time. They used to leave it at 0, which made every waypoint but a
     * death one look created at the epoch. A waypoint rebuilt from a save file or copied from
     * another world keeps its original time through the full constructor below.
     */
    public Waypoint(String name, BlockPos pos, int color, String dimension) {
        this(name, pos, color, dimension, true, false, false, System.currentTimeMillis());
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean visible, boolean focused, boolean death, long createdAtMillis) {
        this.name = name;
        this.pos = pos;
        this.color = color;
        this.dimension = dimension;
        this.visible = visible;
        this.focused = focused;
        this.forceVisible = false;
        this.death = death;
        this.createdAtMillis = createdAtMillis;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BlockPos getPos() {
        return pos;
    }

    public void setPos(BlockPos pos) {
        this.pos = pos;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    /** {@link #getDimension()}, with the Overworld standing in for a missing one (older save files). */
    public String dimensionOrOverworld() {
        return dimension != null ? dimension : DimensionNames.OVERWORLD;
    }

    /**
     * Where this waypoint shows up in {@code viewedDimension}: its position in its own dimension,
     * {@code null} in any other. A waypoint belongs to exactly one dimension since 1.4.0 - the
     * "shared" waypoints that also showed up, converted, in the other half of the Overworld/Nether
     * pair are gone, replaced by an independent copy there (see {@link #convertedPosition} and
     * {@code WaypointStore}'s split of the old ones).
     */
    public BlockPos positionIn(String viewedDimension) {
        return dimensionOrOverworld().equals(viewedDimension) ? pos : null;
    }

    /** The other half of the Overworld/Nether pair this waypoint's dimension belongs to, or
     *  {@code null} for any other dimension - the only pair with a coordinate relationship. */
    public String pairedDimension() {
        return DimensionNames.paired(dimensionOrOverworld());
    }

    /**
     * This waypoint's position converted into {@link #pairedDimension()} at 1:8 (X/Z divided by 8
     * going into the Nether, multiplied coming out, rounded, Y unchanged), or {@code null} outside
     * the pair. The single definition of that conversion: the create screen's "duplicate in the
     * other dimension" places its copy here, and loading a pre-1.4.0 shared waypoint places its
     * split-off copy here too - exactly where the shared one used to be drawn.
     */
    public BlockPos convertedPosition() {
        String own = dimensionOrOverworld();
        if (own.equals(DimensionNames.OVERWORLD)) {
            return new BlockPos(PairedCoordinates.toNether(pos.getX()), pos.getY(), PairedCoordinates.toNether(pos.getZ()));
        }
        if (own.equals(DimensionNames.NETHER)) {
            return new BlockPos(PairedCoordinates.toOverworld(pos.getX()), pos.getY(), PairedCoordinates.toOverworld(pos.getZ()));
        }
        return null;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public boolean isForceVisible() {
        return forceVisible;
    }

    /**
     * Whether this waypoint is drawn in the world: it is visible, and either nothing is focused or
     * it is the focused one or one kept visible beside it. The single definition of that rule -
     * the renderer draws by it and the list's eye icon and dimmed rows show it, which used to be
     * two copies that disagreed (a hidden focused waypoint still showed an open eye).
     *
     * @param anyFocused whether any waypoint of the world is focused, wherever it is - see
     *                   {@link WaypointStore#hasAnyFocused()}.
     */
    public boolean isShown(boolean anyFocused) {
        return !pendingRemoval && visible && (!anyFocused || focused || forceVisible)
                // A hidden group hides its waypoints in the world too - except the focused one,
                // which is the one the player asked to follow.
                && (focused || !WaypointStore.isInHiddenGroup(this));
    }

    /** Whether this waypoint has been deleted from the list and can still be undone - see the field. */
    public boolean isPendingRemoval() {
        return pendingRemoval;
    }

    /** Only {@code WaypointStore} sets this, alongside the countdown it keeps. */
    void setPendingRemoval(boolean pendingRemoval) {
        this.pendingRemoval = pendingRemoval;
    }

    public void setForceVisible(boolean forceVisible) {
        this.forceVisible = forceVisible;
    }

    public boolean isDeath() {
        return death;
    }

    public void setDeath(boolean death) {
        this.death = death;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    /** The groups this waypoint belongs to: a live list, never null, mutated only through
     *  {@link #setGroups}. */
    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups == null ? new ArrayList<>() : new ArrayList<>(groups);
    }

    public boolean isFastPing() {
        return fastPing;
    }

    public void setFastPing(boolean fastPing) {
        this.fastPing = fastPing;
    }

    /** Set once at construction; nothing reassigns it, so there is deliberately no setter. 0 for a
     *  waypoint created before 1.4.0, whose real creation time was never recorded. */
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public String getCachedLabelText() {
        return cachedLabelText;
    }

    public float getCachedLabelWidth() {
        return cachedLabelWidth;
    }

    /** Whether the cached label is still valid for the given inputs, so the caller can skip rebuilding it. */
    public boolean labelCacheMatches(String sourceName, boolean uppercase, boolean showDistance, int distanceKey) {
        return cachedLabelText != null
                && cachedLabelUppercase == uppercase
                && cachedLabelShowDistance == showDistance
                && (!showDistance || cachedLabelDistanceKey == distanceKey)
                && sourceName.equals(cachedLabelSourceName);
    }

    public void setCachedLabel(String sourceName, boolean uppercase, boolean showDistance, int distanceKey, String text, float width) {
        this.cachedLabelSourceName = sourceName;
        this.cachedLabelUppercase = uppercase;
        this.cachedLabelShowDistance = showDistance;
        this.cachedLabelDistanceKey = distanceKey;
        this.cachedLabelText = text;
        this.cachedLabelWidth = width;
    }
}
