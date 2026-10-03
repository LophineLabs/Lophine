package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.*;
import net.minecraft.sounds.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

public class CarpetCactusSoundSourceTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @Test void originalStairHalfSoundPrecedesTheActualSetValueButRotationsDoNotPlayIt(){
        boolean flip=GeneralCompatConfig.flippinCactus,extras=GeneralCompatConfig.flippinCactusExtras;int sound=GeneralCompatConfig.flippinCactusSoundEffect;
        try {
            GeneralCompatConfig.flippinCactus=true;GeneralCompatConfig.flippinCactusExtras=false;GeneralCompatConfig.flippinCactusSoundEffect=1;
            var state=spy(Blocks.OAK_STAIRS.defaultBlockState());var world=mock(Level.class);var player=mock(Player.class);var pos=new BlockPos(4,5,6);when(player.blockPosition()).thenReturn(pos);
            assertTrue(CarpetBlockRotator.flipBlock(state,world,player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(4.5,6,6.5),Direction.UP,pos,false)));
            var order=inOrder(world,state);order.verify(world).playSound(null,pos,SoundEvents.LEVER_CLICK,SoundSource.BLOCKS,1F,0.95F);order.verify(state).setValue(StairBlock.HALF,Half.TOP);order.verify(world).setBlock(eq(pos),any(),eq(Block.UPDATE_CLIENTS|1024));
            clearInvocations(world,state);
            assertTrue(CarpetBlockRotator.flipBlock(state,world,player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(4.5,5.5,6),Direction.NORTH,pos,false)));
            verify(world,never()).playSound(any(),any(BlockPos.class),any(SoundEvent.class),any(SoundSource.class),anyFloat(),anyFloat());
        }finally{GeneralCompatConfig.flippinCactus=flip;GeneralCompatConfig.flippinCactusExtras=extras;GeneralCompatConfig.flippinCactusSoundEffect=sound;}
    }
    @Test void extrasKeepTheirOwnSetBlockDirtySoundTail(){
        boolean flip=GeneralCompatConfig.flippinCactus,extras=GeneralCompatConfig.flippinCactusExtras;int sound=GeneralCompatConfig.flippinCactusSoundEffect;
        try {
            GeneralCompatConfig.flippinCactus=true;GeneralCompatConfig.flippinCactusExtras=true;GeneralCompatConfig.flippinCactusSoundEffect=4;
            var state=spy(Blocks.BARREL.defaultBlockState());var world=mock(Level.class);var player=mock(Player.class);var pos=new BlockPos(4,5,6);when(player.blockPosition()).thenReturn(pos);
            assertTrue(CarpetBlockRotator.flipBlock(state,world,player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false)));
            var opposite=state.getValue(BarrelBlock.FACING).getOpposite();var order=inOrder(world,state);order.verify(state).setValue(BarrelBlock.FACING,opposite);order.verify(world).setBlock(eq(pos),any(),eq(Block.UPDATE_CLIENTS|1024));order.verify(world).setBlocksDirty(eq(pos),eq(state),any());order.verify(world).playSound(null,pos,SoundEvents.VILLAGER_AMBIENT,SoundSource.BLOCKS,1F,1F);
        }finally{GeneralCompatConfig.flippinCactus=flip;GeneralCompatConfig.flippinCactusExtras=extras;GeneralCompatConfig.flippinCactusSoundEffect=sound;}
    }
    @Test void extrasSoundReadsItsOwnEffectAtTheTailAfterTheMainRuleHasChanged() {
        boolean flip=GeneralCompatConfig.flippinCactus,extras=GeneralCompatConfig.flippinCactusExtras;int sound=GeneralCompatConfig.flippinCactusSoundEffect;
        try {
            GeneralCompatConfig.flippinCactus=true;GeneralCompatConfig.flippinCactusExtras=true;GeneralCompatConfig.flippinCactusSoundEffect=1;
            var state=Blocks.BARREL.defaultBlockState();var world=mock(Level.class);var player=mock(Player.class);var pos=BlockPos.ZERO;when(player.blockPosition()).thenReturn(pos);
            doAnswer(call->{GeneralCompatConfig.flippinCactus=false;return null;}).when(world).setBlocksDirty(eq(pos),eq(state),any());
            assertTrue(CarpetBlockRotator.flipBlock(state,world,player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false)));
            verify(world).playSound(null,pos,SoundEvents.LEVER_CLICK,SoundSource.BLOCKS,1F,0.95F);
        }finally{GeneralCompatConfig.flippinCactus=flip;GeneralCompatConfig.flippinCactusExtras=extras;GeneralCompatConfig.flippinCactusSoundEffect=sound;}
    }

}
