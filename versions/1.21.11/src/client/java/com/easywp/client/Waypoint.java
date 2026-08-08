package com.easywp.client;

import net.minecraft.core.BlockPos;

/**
 * Data model for a Waypoint.
 */
public class Waypoint {
    private String name;
    private int x;
    private int y;
    private int z;
    private int color;
    private String dimension;
    private boolean visible = true;
    private boolean focused = false;
    private boolean shared = false;

    public Waypoint(String name, BlockPos pos, int color, String dimension) {
        this(name, pos, color, dimension, false);
    }

    public Waypoint(String name, BlockPos pos, int color, String dimension, boolean shared) {
        this.name = name;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.color = color;
        this.dimension = dimension;
        this.visible = true;
        this.focused = false;
        this.shared = shared;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BlockPos getPos() { return new BlockPos(x, y, z); }
    public void setPos(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isFocused() { return focused; }
    public void setFocused(boolean focused) { this.focused = focused; }

    public boolean isShared() { return shared; }
    public void setShared(boolean shared) { this.shared = shared; }

    public static BlockPos getConvertedPos(BlockPos pos, String fromDim, String toDim) {
        if (fromDim == null || toDim == null || fromDim.equals(toDim)) return pos;

        if (fromDim.equals("minecraft:overworld") && toDim.equals("minecraft:the_nether")) {
            return new BlockPos(
                (int) Math.floor(pos.getX() / 8.0),
                pos.getY(),
                (int) Math.floor(pos.getZ() / 8.0)
            );
        } else if (fromDim.equals("minecraft:the_nether") && toDim.equals("minecraft:overworld")) {
            return new BlockPos(
                pos.getX() * 8,
                pos.getY(),
                pos.getZ() * 8
            );
        }
        return null;
    }
}
