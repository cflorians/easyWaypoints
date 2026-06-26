package com.easywp.client;

import net.minecraft.core.BlockPos;

public class Waypoint {
    private String name;
    private BlockPos pos;
    private int color; // ARGB color

    public Waypoint(String name, BlockPos pos) {
        this(name, pos, 0xFF00FF00); // Default to green (ARGB: green is 0xFF00FF00)
    }

    public Waypoint(String name, BlockPos pos, int color) {
        this.name = name;
        this.pos = pos;
        this.color = color;
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
}
