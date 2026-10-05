package com.easywp.client;

import net.minecraft.core.BlockPos;

import java.util.function.BiConsumer;

public class Waypoint {
    private String name;
    private BlockPos pos;
    private int color;
    private String dimension;
    private boolean shared;
    private boolean visible = true;
    private boolean focused = false;
    private boolean forceVisible = false;
    private boolean death = false;
    private long createdAtMillis = 0L;
    // Set only by setter, like forceVisible: Fast Ping (ModKeyBindings) is the only creation path,
    // which builds a plain Waypoint and flags it after - adding this to the canonical constructor
    // below would mean touching every call site for a flag almost none of them ever need.
    private boolean fastPing = false;

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

    public Waypoint(String name, BlockPos pos, int color, String dimension) {
        this(name, pos, color, dimension, false);
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared) {
        this(name, pos, color, dimension, shared, true, false);
    }

    /**
     * Every shorter constructor ends here, and each one is used only to create a waypoint that did
     * not exist before - the create screen, Fast Ping, a Xaero import - so it is stamped with the
     * current time. They used to leave it at 0, which made every waypoint but a death one look
     * created at the epoch. A waypoint rebuilt from a save file or copied from another world keeps
     * its original time through the full constructor below.
     */
    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared, boolean visible, boolean focused) {
        this(name, pos, color, dimension, shared, visible, focused, false, System.currentTimeMillis());
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared, boolean visible, boolean focused, boolean death, long createdAtMillis) {
        this.name = name;
        this.pos = pos;
        this.color = color;
        this.dimension = dimension;
        this.shared = shared;
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
     * Where this waypoint shows up in {@code viewedDimension}, or {@code null} if it doesn't.
     *
     * <p>Its own dimension gets its own position. A {@code shared} waypoint also shows up in the
     * other half of the Overworld/Nether pair, at the 1:8 converted X/Z (Y unchanged) - the only
     * dimension pair with a coordinate relationship to convert. This, and {@link #forEachPosition},
     * are the single definition of that projection: the renderer, the list screen and the Xaero
     * sync all ask here instead of each carrying its own copy of the ratio.
     */
    public BlockPos positionIn(String viewedDimension) {
        String own = dimensionOrOverworld();
        if (own.equals(viewedDimension)) {
            return pos;
        }
        if (!shared) {
            return null;
        }
        if (own.equals(DimensionNames.OVERWORLD) && viewedDimension.equals(DimensionNames.NETHER)) {
            return scaled(1.0 / 8.0);
        }
        if (own.equals(DimensionNames.NETHER) && viewedDimension.equals(DimensionNames.OVERWORLD)) {
            return scaled(8.0);
        }
        return null;
    }

    /** Every dimension this waypoint shows up in, with its position there - its own first. */
    public void forEachPosition(BiConsumer<String, BlockPos> consumer) {
        String own = dimensionOrOverworld();
        consumer.accept(own, pos);
        if (!shared) {
            return;
        }
        if (own.equals(DimensionNames.OVERWORLD)) {
            consumer.accept(DimensionNames.NETHER, scaled(1.0 / 8.0));
        } else if (own.equals(DimensionNames.NETHER)) {
            consumer.accept(DimensionNames.OVERWORLD, scaled(8.0));
        }
    }

    private BlockPos scaled(double factor) {
        return new BlockPos(
                (int) Math.round(pos.getX() * factor),
                pos.getY(),
                (int) Math.round(pos.getZ() * factor)
        );
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
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
        return visible && (!anyFocused || focused || forceVisible);
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
