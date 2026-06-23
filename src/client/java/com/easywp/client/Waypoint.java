package com.easywp.client;

import net.minecraft.core.BlockPos;

public class Waypoint {
    private final String name;
    private final BlockPos pos;

    public Waypoint(String name, BlockPos pos){
        this.name = name;
        this.pos = pos;
    }

    public String getName(){
        return name;
    }

    public BlockPos getPos(){
        return pos;
    }
}
