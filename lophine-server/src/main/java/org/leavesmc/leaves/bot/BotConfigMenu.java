package org.leavesmc.leaves.bot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.leavesmc.leaves.bot.agent.Configs;
import org.leavesmc.leaves.bot.agent.configs.AbstractBotConfig;
import org.leavesmc.leaves.entity.bot.CraftBot;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BotConfigMenu implements Listener {

    private static final Map<UUID, Long> lastClick = new HashMap<>();
    private static final long COOLDOWN_MS = 250;

    private static final int[] CONFIG_SLOTS = {9, 10, 11, 12, 13, 14, 15};
    private static final AbstractBotConfig<?, ?>[] CONFIGS = {
        Configs.SKIP_SLEEP, Configs.ALWAYS_SEND_DATA, Configs.SPAWN_PHANTOM,
        Configs.KEEP_INVENTORY, Configs.SIMULATION_DISTANCE,
        Configs.TICK_TYPE, Configs.ENABLE_LOCATOR_BAR
    };

    private static boolean registered = false;

    public static void open(ServerPlayer player, ServerBot bot) {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new BotConfigMenu(), MinecraftInternalPlugin.INSTANCE);
            registered = true;
        }

        Inventory inv = Bukkit.createInventory(new ConfigHolder(bot), 54, "§8⚙ " + bot.getBukkitEntity().getName() + " §8的设置");
        populate(inv, bot);
        player.getBukkitEntity().openInventory(inv);
    }

    private static void populate(Inventory inv, ServerBot bot) {
        ItemStack border = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");

        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        for (int i = 36; i < 45; i++) inv.setItem(i, border);
        inv.setItem(47, border);
        inv.setItem(48, border);
        inv.setItem(49, border);
        inv.setItem(50, border);
        inv.setItem(51, border);
        inv.setItem(52, border);

        for (int i = 0; i < CONFIG_SLOTS.length; i++) {
            inv.setItem(CONFIG_SLOTS[i], createConfigItem(CONFIGS[i], bot));
        }

        ItemStack info = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7↑ §7点击配置项修改");
        for (int i = 36; i < 45; i++) inv.setItem(i, info);

        ItemStack inventoryBtn = createItem(Material.CHEST, "§a← §a打开背包", "§7点击查看该假人背包");
        inv.setItem(45, inventoryBtn);

        ItemStack closeBtn = createItem(Material.BARRIER, "§c✕ §c关闭");
        inv.setItem(53, closeBtn);
    }

    private static ItemStack createConfigItem(AbstractBotConfig<?, ?> config, ServerBot bot) {
        String name = config.getName();
        Object value = bot.getConfigValue(config);

        Material mat;
        String displayName;
        String loreValue;

        if (config == Configs.SIMULATION_DISTANCE) {
            mat = Material.COMPARATOR;
            displayName = "§b模拟距离";
            loreValue = "§7当前值: §f" + value + "  §e点击选择数值";
        } else if (config == Configs.TICK_TYPE) {
            mat = value.toString().equals("NETWORK") ? Material.REDSTONE_TORCH : Material.REDSTONE_BLOCK;
            displayName = "§bTick 类型";
            loreValue = "§7当前值: §f" + value + "  §e点击切换";
        } else {
            boolean boolVal = value instanceof Boolean && (Boolean) value;
            mat = boolVal ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            String status = boolVal ? "§a✓ 已启用" : "§c✗ 已禁用";
            displayName = "§b" + getChineseName(name);
            loreValue = status + "  §e点击切换";
        }

        return createItem(mat, displayName, loreValue);
    }

    private static String getChineseName(String configName) {
        return switch (configName) {
            case "skip_sleep" -> "跳过睡眠";
            case "always_send_data" -> "始终发送数据";
            case "spawn_phantom" -> "生成幻翼";
            case "enable_locator_bar" -> "定位器栏";
            case "keep_inventory" -> "死亡不掉落";
            case "simulation_distance" -> "模拟距离";
            case "tick_type" -> "Tick 类型";
            default -> configName;
        };
    }

    private static ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfigHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        event.setCancelled(true);

        int slot = event.getSlot();
        ServerBot bot = holder.getBot();

        if (bot == null || Bukkit.getPlayer(player.getUniqueId()) == null) return;
        if (!bot.hasManagePermission(player.getUniqueId()) && !player.isOp()) {
            player.sendMessage(Component.text("你没有权限管理该假人", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;
        lastClick.put(player.getUniqueId(), now);

        for (int i = 0; i < CONFIG_SLOTS.length; i++) {
            if (slot == CONFIG_SLOTS[i]) {
                handleConfigClick(bot, CONFIGS[i], player);
                event.getView().getTopInventory().clear();
                populate(event.getView().getTopInventory(), bot);
                return;
            }
        }

        if (slot == 45) {
            player.closeInventory();
            openBotInventory(player, bot);
        } else if (slot == 53) {
            player.closeInventory();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void handleConfigClick(ServerBot bot, AbstractBotConfig config, Player player) {
        AbstractBotConfig botConfig = bot.getConfig(config);
        if (botConfig == null) {
            player.sendMessage(Component.text("获取配置实例失败", NamedTextColor.RED));
            return;
        }
        if (config == Configs.SIMULATION_DISTANCE) {
            String botName = bot.getBukkitEntity().getName();
            player.sendMessage(
                Component.text("选择模拟距离：", NamedTextColor.GOLD)
                    .append(Component.text(" [4]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance 4")))
                    .append(Component.text(" [8]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance 8")))
                    .append(Component.text(" [12]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance 12")))
                    .append(Component.text(" [16]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance 16")))
                    .append(Component.text(" [32]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance 32")))
                    .append(Component.text(" [默认]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/player " + botName + " config simulation_distance -1")))
            );
        } else if (config == Configs.TICK_TYPE) {
            ServerBot.TickType current = (ServerBot.TickType) botConfig.getValue();
            ServerBot.TickType next = current == ServerBot.TickType.NETWORK
                ? ServerBot.TickType.ENTITY_LIST
                : ServerBot.TickType.NETWORK;
            try {
                botConfig.setValue(next);
                BotList.INSTANCE.saveBotConfigs(bot);
                player.sendMessage(Component.text("已将 TickType 切换为 ", NamedTextColor.GREEN)
                    .append(Component.text(next.toString(), NamedTextColor.AQUA)));
            } catch (Exception e) {
                player.sendMessage(Component.text("设置失败", NamedTextColor.RED));
            }
        } else {
            boolean current = botConfig.getValue() instanceof Boolean && (Boolean) botConfig.getValue();
            try {
                botConfig.setValue(!current);
                BotList.INSTANCE.saveBotConfigs(bot);
                String cn = getChineseName(config.getName());
                player.sendMessage(Component.text("已" + (!current ? "启用" : "禁用") + " " + cn, NamedTextColor.GREEN));
            } catch (Exception e) {
                player.sendMessage(Component.text("设置失败", NamedTextColor.RED));
            }
        }
    }

    private static void openBotInventory(Player player, ServerBot bot) {
        if (player.getWorld().equals(bot.getBukkitEntity().getWorld())
            && player.getLocation().distanceSquared(bot.getBukkitEntity().getLocation()) < 64 * 64) {
            ((ServerPlayer) ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle())
                .openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (i, inv, p) -> net.minecraft.world.inventory.ChestMenu.sixRows(i, inv, bot.getBotContainer()),
                    bot.getDisplayName()
                ));
        } else {
            player.sendMessage(Component.text("你离假人太远了，无法打开背包", NamedTextColor.RED));
        }
    }

    private static class ConfigHolder implements InventoryHolder {
        private final ServerBot bot;

        ConfigHolder(ServerBot bot) {
            this.bot = bot;
        }

        public ServerBot getBot() {
            return bot;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
