"""Compile the real prediction-fence state logic and WeakIdentityMap with minimal session types."""
from pathlib import Path
import os
import argparse
import subprocess

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True)
p.add_argument('--expect',choices=['broken','fixed'],required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--java-home', type=Path, required=True)
a=p.parse_args();repo=a.root.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
fence=repo/'lophine-server/src/main/java/fun/bm/lophine/carpet/CarpetBlockPredictionFence.java'
text=fence.read_text(encoding='utf-8-sig')
# The excluded resync method schedules native world/entity actors; these checks target retain/readyThrough only.
text=text[:text.index('    /**\n     * Read block/BE snapshots')]+'}\n'
text='\n'.join(line for line in text.splitlines() if not line.startswith('import '))
imports='''
import carpet.script.external.WeakIdentityMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.lang.ref.WeakReference;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
'''
text=text.replace('package fun.bm.lophine.carpet;','package fun.bm.lophine.carpet;\n'+imports)
files={
 'fun/bm/lophine/carpet/CarpetBlockPredictionFence.java':text,
 'net/minecraft/server/level/ServerLevel.java':'package net.minecraft.server.level; public class ServerLevel {}',
 'net/minecraft/server/level/ServerPlayer.java':'''package net.minecraft.server.level; public class ServerPlayer {
 public ServerLevel world; public ServerPlayer(ServerLevel world){this.world=world;} public ServerLevel level(){return world;}}''',
 'net/minecraft/server/network/ServerGamePacketListenerImpl.java':'''package net.minecraft.server.network;
 import net.minecraft.server.level.ServerPlayer; public class ServerGamePacketListenerImpl {
 public ServerPlayer player; public ServerGamePacketListenerImpl(ServerPlayer player){this.player=player;}}''',
 'FenceScopeCheck.java':'''import fun.bm.lophine.carpet.CarpetBlockPredictionFence;
import net.minecraft.server.level.*;import net.minecraft.server.network.*;import java.util.concurrent.*;
public class FenceScopeCheck {
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  boolean broken=Boolean.parseBoolean(args[0]);
  var worldA=new ServerLevel();var worldB=new ServerLevel();var c=new ServerGamePacketListenerImpl(new ServerPlayer(worldA));
  var old=new CompletableFuture<Void>();CarpetBlockPredictionFence.retain(c,1,old);
  require(CarpetBlockPredictionFence.readyThrough(c,4)==0,"pending same-world operation was not fenced");
  c.player.world=worldB;
  require(CarpetBlockPredictionFence.readyThrough(c,4)==(broken?0:4),"old-world future leaked into current prediction epoch");
  if(!broken){
   var fresh=new CompletableFuture<Void>();CarpetBlockPredictionFence.retain(c,1,fresh);old.complete(null);
   require(CarpetBlockPredictionFence.readyThrough(c,4)==0,"old completion removed current fence");
   fresh.complete(null);require(CarpetBlockPredictionFence.readyThrough(c,4)==4,"current completion did not release fence");
   var stalePlayer=new CompletableFuture<Void>();CarpetBlockPredictionFence.retain(c,1,stalePlayer);
   c.player=new ServerPlayer(worldB);require(CarpetBlockPredictionFence.readyThrough(c,4)==4,"old player retained after respawn");
  }
  var same=new ServerGamePacketListenerImpl(new ServerPlayer(worldA));var one=new CompletableFuture<Void>();var two=new CompletableFuture<Void>();
  CarpetBlockPredictionFence.retain(same,3,one);CarpetBlockPredictionFence.retain(same,3,two);
  one.complete(null);require(CarpetBlockPredictionFence.readyThrough(same,5)==2,"overlapping actions acknowledged too early");
  two.complete(null);require(CarpetBlockPredictionFence.readyThrough(same,5)==5,"overlapping actions did not clear");
  var failed=new CompletableFuture<Void>();CarpetBlockPredictionFence.retain(same,7,failed);failed.completeExceptionally(new RuntimeException("expected"));
  require(CarpetBlockPredictionFence.readyThrough(same,9)==9,"failed action left permanent fence");
  System.out.println(broken?"PASS: original fence retains pending operations across client world changes":"PASS: real fence scopes pending operations to current world/player; old completions, overlapping actions and failures checked");
 }
}'''}
sources=[]
for relative,content in files.items():
 path=out/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');sources.append(path)
sources.append(repo/'lophine-server/src/main/java/carpet/script/external/WeakIdentityMap.java')
subprocess.run([str(a.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')),'-encoding','UTF-8','-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(a.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',str(out),'FenceScopeCheck',str(a.expect=='broken').lower()],check=True)
