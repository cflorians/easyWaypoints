package com.easywp.client;

import net.minecraft.core.BlockPos;

public class Waypoint {
    private String name;
    private BlockPos pos;
    private int color; // ARGB color
    private String dimension;
    private boolean shared;
    private boolean visible = true;
    private boolean focused = false;
    private boolean forceVisible = false;
    
    public Waypoint(String name, BlockPos pos) {
        this(name, pos, 0xFF00FF00); // Default to green (ARGB: green is 0xFF00FF00)
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
        this.name = name;
        this.pos = pos;
        this.color = color;
        this.dimension = dimension;
        this.shared = shared;
        this.visible = visible;
        this.focused = focused;
        this.forceVisible = false;
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
}
