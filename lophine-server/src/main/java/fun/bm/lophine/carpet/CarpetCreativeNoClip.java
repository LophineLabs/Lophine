package fun.bm.lophine.carpet;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.world.entity.player.Player;
/** The pinned Carpet server predicate used by placement and portal behavior. */
public final class CarpetCreativeNoClip{
 private CarpetCreativeNoClip(){}
 public static boolean active(Player player){return GeneralCompatConfig.creativeNoClip&&player!=null&&player.isCreative()&&player.getAbilities().flying;}
}
