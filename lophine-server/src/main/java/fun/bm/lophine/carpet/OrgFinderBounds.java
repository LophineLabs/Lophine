// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Pinned inclusive horizontal limits and actual world construction height. */
record OrgFinderBounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
    static final class TooBig extends IllegalArgumentException {TooBig(){super(OrgFinderText.finder("toobig",1025).getString());}}
    static OrgFinderBounds of(ServerLevel world,BlockPos from,BlockPos to){
        return of(world,from,to,true);
    }
    static OrgFinderBounds ofEntities(ServerLevel world,BlockPos from,BlockPos to){return of(world,from,to,false);}
    private static OrgFinderBounds of(ServerLevel world,BlockPos from,BlockPos to,boolean architecture){
        var result=new OrgFinderBounds(Math.max(-Level.MAX_LEVEL_SIZE,Math.min(from.getX(),to.getX())),Math.max(world.getMinY(),Math.min(from.getY(),to.getY())),
            Math.max(-Level.MAX_LEVEL_SIZE,Math.min(from.getZ(),to.getZ())),Math.min(Level.MAX_LEVEL_SIZE,Math.max(from.getX(),to.getX())),
            Math.min(world.getMaxY(),Math.max(from.getY(),to.getY())),Math.min(Level.MAX_LEVEL_SIZE,Math.max(from.getZ(),to.getZ())));
        if(!architecture)result=new OrgFinderBounds(result.minX,Math.max(Level.MIN_ENTITY_SPAWN_Y,Math.min(from.getY(),to.getY())),result.minZ,result.maxX,Math.min(Level.MAX_ENTITY_SPAWN_Y,Math.max(from.getY(),to.getY())),result.maxZ);
        if((long)result.maxX-result.minX+1>1025||(long)result.maxZ-result.minZ+1>1025)throw new TooBig();
        return result;
    }
    static OrgFinderBounds radius(ServerLevel world,BlockPos center,int radius){
        if(radius<0||radius>512)throw new IllegalArgumentException("Finder radius must be between 0 and 512");
        return of(world,new BlockPos(center.getX()-radius,world.getMinY(),center.getZ()-radius),new BlockPos(center.getX()+radius,world.getMaxY(),center.getZ()+radius));
    }
    boolean empty(){return minX>maxX||minY>maxY||minZ>maxZ;}
    long size(){return (long)Math.max(0,maxX-minX+1)*Math.max(0,maxY-minY+1)*Math.max(0,maxZ-minZ+1);}
    boolean contains(BlockPos position){return position.getX()>=minX&&position.getX()<=maxX&&position.getY()>=minY&&position.getY()<=maxY&&position.getZ()>=minZ&&position.getZ()<=maxZ;}
    AABB box(){return new AABB(minX,minY,minZ,(double)maxX+1,(double)maxY+1,(double)maxZ+1);}
}
