package com.easywp.client;

import net.minecraft.core.BlockPos;

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

    // Render-side cache of the on-screen label built for this waypoint (see WaypointRenderer).
    // Lives here instead of a separate map because the renderer already reads and writes waypoint
    // state directly with no store abstraction in between - this just rides along with it, and
    // needs no cleanup when a waypoint is deleted since it goes away with the object.
    private String cachedLabelSourceName;
    private boolean cachedLabelUppercase;
    private boolean cachedLabelShowDistance;
    private int cachedLabelDistance;
    private String cachedLabelText;
    private float cachedLabelWidth;

    public Waypoint(String name, BlockPos pos) {
        this(name, pos, 0xFF00FF00);
    }

    public Waypoint(String name, BlockPos pos, int color) {
        this(name, pos, color, "minecraft:overworld");
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension) {
        this(name, pos, color, dimension, false);
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared) {
        this(name, pos, color, dimension, shared, true, false);
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared, boolean visible, boolean focused) {
        this(name, pos, color, dimension, shared, visible, focused, false, 0L);
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

    public void setForceVisible(boolean forceVisible) {
        this.forceVisible = forceVisible;
    }

    public boolean isDeath() {
        return death;
    }

    public void setDeath(boolean death) {
        this.death = death;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
    }

    public String getCachedLabelText() {
        return cachedLabelText;
    }

    public float getCachedLabelWidth() {
        return cachedLabelWidth;
    }

    /** Whether the cached label is still valid for the given inputs, so the caller can skip rebuilding it. */
    public boolean labelCacheMatches(String sourceName, boolean uppercase, boolean showDistance, int distanceMeters) {
        return cachedLabelText != null
                && cachedLabelUppercase == uppercase
                && cachedLabelShowDistance == showDistance
                && (!showDistance || cachedLabelDistance == distanceMeters)
                && sourceName.equals(cachedLabelSourceName);
    }

    public void setCachedLabel(String sourceName, boolean uppercase, boolean showDistance, int distanceMeters, String text, float width) {
        this.cachedLabelSourceName = sourceName;
        this.cachedLabelUppercase = uppercase;
        this.cachedLabelShowDistance = showDistance;
        this.cachedLabelDistance = distanceMeters;
        this.cachedLabelText = text;
        this.cachedLabelWidth = width;
    }
}
