package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OrgPlayerExtensionEntryPointsTest {
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @Test void exactOriginalEscAndCamelCaseEnderChestRoutesHaveRealHandlersAndCurrentPermissionGates(){
        var dispatcher=new CommandDispatcher<CommandSourceStack>();var source=mock(CommandSourceStack.class);when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
        String permission=GeneralCompatConfig.playerCommandOpenPlayerInventory;boolean close=GeneralCompatConfig.playerCommandCloseScreen;
        try{
            GeneralCompatConfig.playerCommandOpenPlayerInventory="true";GeneralCompatConfig.playerCommandCloseScreen=true;OrgPlayerInventoryMenus.register(dispatcher);
            var target=dispatcher.getRoot().getChild("player").getChild("player");assertSame(target.getChild("esc").getCommand(),target.getChild("closeScreen").getCommand());
            for(String command:java.util.List.of("player Ada esc","player Ada closeScreen","player Ada enderChest","player Ada enderchest","player Ada inventory")){
                var parsed=dispatcher.parse(command,source);assertFalse(parsed.getReader().canRead(),command);assertNotNull(parsed.getContext().getCommand(),command);
            }
            GeneralCompatConfig.playerCommandOpenPlayerInventory="false";GeneralCompatConfig.playerCommandCloseScreen=false;
            assertFalse(target.getChild("enderChest").canUse(source));assertFalse(target.getChild("esc").canUse(source));
        }finally{GeneralCompatConfig.playerCommandOpenPlayerInventory=permission;GeneralCompatConfig.playerCommandCloseScreen=close;}
    }
}
