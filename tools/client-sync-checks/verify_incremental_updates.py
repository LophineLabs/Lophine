"""Exercise production dirty-marking and broadcast queue methods with controlled chunk readiness."""
from pathlib import Path
import os
import argparse
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--expect', choices=['broken', 'fixed'], required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
args = parser.parse_args()
repo, out = args.root.resolve(), args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
nms = repo / 'lophine-server/src/minecraft/java/net/minecraft/server/level'

def extract(text, signature):
    start = text.index(signature)
    at = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[at] == '{') - (text[at] == '}')
        at += 1
    return text[start:at]

holder = (nms / 'ChunkHolder.java').read_text(encoding='utf-8-sig')
cache = (nms / 'ServerChunkCache.java').read_text(encoding='utf-8-sig')
methods = '\n'.join(extract(holder, signature) for signature in (
    '    public boolean blockChanged(final BlockPos pos)',
    '    public boolean sectionLightChanged(final LightLayer layer, final int chunkY)',
    '    public boolean hasChangesToBroadcast()'))
broadcast = extract(cache, '    private void broadcastChangedChunks(final ProfilerFiller profiler)')
broadcast = broadcast.replace('io.papermc.paper.threadedregions.RegionizedWorldData', 'WorldData')
support = r'''
import java.util.*;
import it.unimi.dsi.fastutil.shorts.*;
public class IncrementalUpdateCheck {
 record BlockPos(int x,int y,int z) { int getY(){return y;} }
 static class SectionPos { static short sectionRelativePos(BlockPos p){return (short)((p.x()&15)<<8|(p.z()&15)<<4|(p.y()&15));} }
 enum LightLayer { SKY, BLOCK }
 enum ChunkStatus { INITIALIZE_LIGHT }
 static class ChunkAccess { void markUnsaved(){} }
 static class LevelChunk extends ChunkAccess {}
 static class LightEngine { int getMinLightSection(){return 0;} int getMaxLightSection(){return 17;} }
 static class Height { int getSectionIndex(int y){return y>>4;} }
 static class ProfilerFiller { void push(String s){} void pop(){} }
 static class WorldData { final Set<ChunkHolder> chunkHoldersToBroadcast=new LinkedHashSet<>(); }
 static class Level { final WorldData data=new WorldData(); WorldData getCurrentWorldData(){return data;} }
 static class ChunkHolder {
   boolean full=true, neighbours=true, hasChangedSections;
   final List<Object> playersSentChunkTo=new ArrayList<>(List.of(new Object()));
   final ShortSet[] changedBlocksPerSection=new ShortSet[16];
   final BitSet skyChangedLightSectionFilter=new BitSet(),blockChangedLightSectionFilter=new BitSet();
   final LevelChunk chunk=new LevelChunk(); final LightEngine lightEngine=new LightEngine();
   final Height levelHeightAccessor=new Height(); int broadcasts;
   LevelChunk getChunkToSend(){return full&&neighbours?chunk:null;}
   LevelChunk moonrise$getFullChunk(){return full?chunk:null;}
   ChunkAccess getChunkIfPresent(ChunkStatus s){return full?chunk:null;}
   void broadcastChanges(LevelChunk c){
     if(hasChangesToBroadcast()) broadcasts++;
     hasChangedSections=false; Arrays.fill(changedBlocksPerSection,null);
     skyChangedLightSectionFilter.clear(); blockChangedLightSectionFilter.clear();
   }
HOLDER_METHODS
 }
 final Level level=new Level(); final ChunkHolder holder=new ChunkHolder();
 void mark(int x){if(holder.blockChanged(new BlockPos(x,64,0))) level.data.chunkHoldersToBroadcast.add(holder);}
 void light(){if(holder.sectionLightChanged(LightLayer.SKY,4)) level.data.chunkHoldersToBroadcast.add(holder);}
 void flush(){broadcastChangedChunks(new ProfilerFiller());}
BROADCAST_METHOD
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
   boolean broken=Boolean.parseBoolean(args[0]);
   {var t=new IncrementalUpdateCheck();t.holder.neighbours=false;t.mark(1);
    if(broken){require(!t.holder.hasChangedSections&&t.level.data.chunkHoldersToBroadcast.isEmpty(),"old mark no longer reproduces dropped update");}
    else {require(t.holder.hasChangedSections,"unready neighbour dropped block update");t.flush();require(t.holder.broadcasts==1,"incremental packet not broadcast");}
   }
   {var t=new IncrementalUpdateCheck();t.mark(1);t.holder.neighbours=false;t.flush();
    if(broken){require(t.holder.hasChangedSections&&t.level.data.chunkHoldersToBroadcast.isEmpty(),"old queue loss did not reproduce");t.holder.neighbours=true;t.mark(2);t.flush();require(t.holder.broadcasts==0,"old update unexpectedly recovered");}
    else {require(!t.holder.hasChangedSections&&t.holder.broadcasts==1,"queued update lost when neighbour became unavailable");t.holder.neighbours=true;t.mark(2);t.flush();require(t.holder.broadcasts==2,"subsequent block update stuck");}
   }
   {var t=new IncrementalUpdateCheck();t.holder.neighbours=false;t.light();t.flush();
    require(t.holder.broadcasts==(broken?0:1),"light update readiness mismatch");}
   {var t=new IncrementalUpdateCheck();t.holder.playersSentChunkTo.clear();t.mark(1);t.light();t.flush();require(t.holder.broadcasts==0,"update sent without recipients");}
   {var t=new IncrementalUpdateCheck();t.holder.full=false;t.mark(1);t.light();t.flush();require(t.holder.broadcasts==0,"unloaded chunk was used");}
   {var t=new IncrementalUpdateCheck();t.mark(1);t.mark(2);t.flush();require(t.holder.broadcasts==1,"normal coalescing changed");t.mark(3);t.flush();require(t.holder.broadcasts==2,"normal follow-up update failed");}
   {var t=new IncrementalUpdateCheck();t.holder.neighbours=false;require(t.holder.getChunkToSend()==null,"initial whole-chunk send guard changed");}
   System.out.println(broken?"PASS: production methods reproduce dropped block/light updates and a permanently stranded dirty queue":"PASS: incremental block/light updates survive missing neighbours; follow-up updates, no recipients, unloaded chunks, coalescing and initial-send guard checked");
 }
}
'''
source = support.replace('HOLDER_METHODS', methods).replace('BROADCAST_METHOD', broadcast)
java = out / 'IncrementalUpdateCheck.java'
java.write_text(source, encoding='utf-8')
fastutil = next((repo / 'lophine-server/.gradle/caches/paperweight/jars/minecraft/it/unimi/dsi/fastutil').glob('*/*.jar'))
subprocess.run([str(args.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')), '-encoding', 'UTF-8', '-cp', str(fastutil), '-d', str(out), str(java)], check=True)
subprocess.run([str(args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')), '-cp', os.pathsep.join([str(out),str(fastutil)]), 'IncrementalUpdateCheck', str(args.expect=='broken').lower()], check=True)
