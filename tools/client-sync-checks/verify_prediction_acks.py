"""Regression checks using the production ack method and prediction-fence implementation."""
from pathlib import Path
import os
import argparse
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--root',type=Path,required=True)
p.add_argument('--expect',choices=['broken','fixed'],required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--client-jar',type=Path)
p.add_argument('--java-home', type=Path, required=True)
a=p.parse_args(); repo=a.root.resolve(); out=a.output.resolve(); out.mkdir(parents=True,exist_ok=True)
def extract(text,signature):
 start=text.index(signature); at=text.index('{',start)+1; depth=1
 while depth:
  depth+=(text[at]=='{')-(text[at]=='}'); at+=1
 return text[start:at]
listener=repo/'lophine-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java'
listener_text=listener.read_text(encoding='utf-8-sig')
method=extract(listener_text,'    private void carpetFlushBlockAcks()')
method+='\n'+extract(listener_text,'    public void ackBlockChangesUpTo(final int packetSequenceNr)')
if '    private void carpetEnsureBlockAckContext()' in listener_text:
 method+='\n'+extract(listener_text,'    private void carpetEnsureBlockAckContext()')
method=method.replace('fun.bm.lophine.carpet.CarpetBlockPredictionFence.readyThrough(this, this.ackBlockChangesUpTo)','this.readyThrough(this.ackBlockChangesUpTo)')
method=method.replace('org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION','"ILLEGAL_ACTION"')
support='''
import java.util.*;
public class AckCheck {
 int ackBlockChangesUpTo=-1,carpetLastBlockAck=-1,holdAt=Integer.MAX_VALUE;
 static class ServerLevel{}
 static class ServerPlayer{ServerLevel world=new ServerLevel();ServerLevel level(){return world;}}
 ServerPlayer player=new ServerPlayer(),carpetBlockAckPlayer;
 ServerLevel carpetBlockAckWorld;
 static class Component{static String literal(String s){return s;}}
 boolean disconnected;
 void disconnect(String text,String cause){disconnected=true;}
 final List<Integer> sent=new ArrayList<>();
 record ClientboundBlockChangedAckPacket(int sequence){}
 void send(ClientboundBlockChangedAckPacket p){sent.add(p.sequence());}
 int readyThrough(int requested){return Math.min(requested,holdAt-1);}
METHOD
 static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
 public static void main(String[] args){
  boolean broken=Boolean.parseBoolean(args[0]);
  var c=new AckCheck();c.ackBlockChangesUpTo(900);c.carpetFlushBlockAcks();
  c.player.world=new ServerLevel();c.ackBlockChangesUpTo(1);c.carpetFlushBlockAcks();
  require(c.sent.equals(broken?List.of(900):List.of(900,1)),"sequence restart across dimensions");
  require(c.ackBlockChangesUpTo==-1,"request did not clear");
  var f=new AckCheck();f.ackBlockChangesUpTo(5);f.holdAt=3;f.carpetFlushBlockAcks();
  require(f.sent.equals(List.of(2))&&f.ackBlockChangesUpTo==5,"pending action acknowledged too early");
  f.holdAt=Integer.MAX_VALUE;f.carpetFlushBlockAcks();require(f.sent.equals(List.of(2,5)),"ready action not acknowledged");
  var zero=new AckCheck();zero.ackBlockChangesUpTo(0);zero.holdAt=0;zero.carpetFlushBlockAcks();
  require(zero.sent.isEmpty(),"negative acknowledgement sent");zero.holdAt=Integer.MAX_VALUE;zero.carpetFlushBlockAcks();require(zero.sent.equals(List.of(0)),"sequence zero not acknowledged");
  if(!broken){
   var stale=new AckCheck();stale.ackBlockChangesUpTo(900);stale.holdAt=900;stale.carpetFlushBlockAcks();
   stale.player.world=new ServerLevel();stale.holdAt=Integer.MAX_VALUE;stale.carpetFlushBlockAcks();
   require(stale.sent.equals(List.of(899))&&stale.ackBlockChangesUpTo==-1,"old-world ack leaked into new world");
   stale.ackBlockChangesUpTo(1);stale.carpetFlushBlockAcks();require(stale.sent.equals(List.of(899,1)),"new world did not resume after discarded old ack");
   var respawn=new AckCheck();respawn.ackBlockChangesUpTo(900);respawn.holdAt=900;respawn.carpetFlushBlockAcks();
   respawn.player=new ServerPlayer();respawn.holdAt=Integer.MAX_VALUE;respawn.ackBlockChangesUpTo(1);respawn.carpetFlushBlockAcks();
   require(respawn.sent.equals(List.of(899,1)),"new player inherited old ack request");
  }
  var invalid=new AckCheck();try{invalid.ackBlockChangesUpTo(-1);throw new AssertionError("negative sequence accepted");}catch(IllegalArgumentException expected){require(invalid.disconnected,"invalid sequence did not disconnect");}
  System.out.println(broken?"PASS: original production ack method drops new-world sequence 1 after old-world sequence 900":"PASS: production ack method accepts restarted sequences and preserves async fences/sequence zero");
 }
}
'''
(out/'AckCheck.java').write_text(support.replace('METHOD',method),encoding='utf-8')
sources=[out/'AckCheck.java']
if a.client_jar:
 (out/'ClientSequenceCheck.java').write_text('''
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
public class ClientSequenceCheck {
 public static void main(String[] args){
  var oldWorld=new BlockStatePredictionHandler();for(int i=0;i<900;i++)oldWorld.startPredicting().close();
  var nextWorld=new BlockStatePredictionHandler();nextWorld.startPredicting().close();
  if(oldWorld.currentSequence()!=900||nextWorld.currentSequence()!=1)throw new AssertionError("official client sequence scope changed");
  System.out.println("PASS: actual official 26.3 client resets prediction sequence to 1 in a new handler");
 }
}
''',encoding='utf-8');sources.append(out/'ClientSequenceCheck.java')
cp=[]
if a.client_jar: cp.append(str(a.client_jar.resolve()))
fastutil=next((repo/'lophine-server/.gradle/caches/paperweight/jars/minecraft/it/unimi/dsi/fastutil').glob('*/*.jar'))
cp.append(str(fastutil))
subprocess.run([str(a.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')),'-encoding','UTF-8','-cp',os.pathsep.join(cp),'-d',str(out),*map(str,sources)],check=True)
runtime_cp=os.pathsep.join([str(out),*cp])
subprocess.run([str(a.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',runtime_cp,'AckCheck',str(a.expect=='broken').lower()],check=True)
if a.client_jar: subprocess.run([str(a.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')),'-cp',runtime_cp,'ClientSequenceCheck'],check=True)
