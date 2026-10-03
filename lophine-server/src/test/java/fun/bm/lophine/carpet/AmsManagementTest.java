package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AmsManagementTest {
    @TempDir Path directory;

    @Test void customRootPermissionsRestoreAndKeepCheatPolicies() {
        String old=GeneralCompatConfig.commandCustomCommandPermissionLevel;
        boolean ams=GeneralCompatConfig.preventAdministratorCheat,tis=GeneralCompatConfig.opPlayerNoCheat;
        var saved=java.util.Map.copyOf(AmsManagementSettings.PERMISSIONS);
        try {
            GeneralCompatConfig.commandCustomCommandPermissionLevel="true";
            GeneralCompatConfig.preventAdministratorCheat=false;
            GeneralCompatConfig.opPlayerNoCheat=false;
            var dispatcher=new CommandDispatcher<CommandSourceStack>();
            var node=dispatcher.register(Commands.literal("give").requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions())));
            CommandSourceStack source=mock(CommandSourceStack.class);
            when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);
            when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
            AmsCommandPermissionLevels.apply(dispatcher);
            assertFalse(node.canUse(source));
            AmsManagementSettings.PERMISSIONS.put("give",0);
            assertTrue(node.canUse(source));
            GeneralCompatConfig.preventAdministratorCheat=true;
            assertFalse(node.canUse(source));
            GeneralCompatConfig.preventAdministratorCheat=false;
            GeneralCompatConfig.opPlayerNoCheat=true;
            assertFalse(node.canUse(source));
            GeneralCompatConfig.opPlayerNoCheat=false;
            GeneralCompatConfig.commandCustomCommandPermissionLevel="false";
            assertFalse(node.canUse(source));
            when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
            assertTrue(node.canUse(source));
            for(int i=0;i<20;i++) AmsCommandPermissionLevels.apply(dispatcher);
            assertTrue(node.canUse(source));
            AmsManagementSettings.PERMISSIONS.clear();
            when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);
            assertFalse(node.canUse(source));
        } finally {
            GeneralCompatConfig.commandCustomCommandPermissionLevel=old;
            GeneralCompatConfig.preventAdministratorCheat=ams;
            GeneralCompatConfig.opPlayerNoCheat=tis;
            AmsManagementSettings.PERMISSIONS.clear(); AmsManagementSettings.PERMISSIONS.putAll(saved);
        }
    }

    @Test void malformedListIsRetainedAndNeverPartiallyPublished() throws Exception {
        MinecraftServer server=mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        Path path=directory.resolve("carpetamsaddition/custom_anti_fire_items.json");
        Files.createDirectories(path.getParent());
        String malformed="[\"minecraft:diamond\", {}]";
        Files.writeString(path,malformed);
        var saved=java.util.Set.copyOf(AmsManagementSettings.ANTI_FIRE);
        try {
            AmsManagementSettings.load(server);
            assertTrue(AmsManagementSettings.ANTI_FIRE.isEmpty());
            AmsManagementSettings.ANTI_FIRE.add("minecraft:gold_ingot");
            AmsManagementSettings.saveSet(server,"custom_anti_fire_items",AmsManagementSettings.ANTI_FIRE);
            AmsManagementSettings.flushAtShutdown();
            assertEquals(malformed,Files.readString(path));
        } finally { AmsManagementSettings.ANTI_FIRE.clear(); AmsManagementSettings.ANTI_FIRE.addAll(saved); }
    }

    @Test void storageKeepsUpstreamFlatJsonAcrossReload() throws Exception {
        MinecraftServer server=mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        AmsManagementSettings.load(server);
        AmsManagementSettings.PERMISSIONS.put("give",1);
        AmsManagementSettings.savePermissions(server);
        AmsManagementSettings.PERMISSIONS.put("give",3);
        AmsManagementSettings.savePermissions(server);
        AmsManagementSettings.flushAtShutdown();
        assertEquals(3,com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("carpetamsaddition/custom_command_permission_level.json"))).getAsJsonObject().get("give").getAsInt());
        MinecraftServer restarted=mock(MinecraftServer.class);
        when(restarted.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        AmsManagementSettings.load(restarted);
        assertEquals(3,AmsManagementSettings.PERMISSIONS.get("give"));
        AmsManagementSettings.PERMISSIONS.clear();
    }
}
