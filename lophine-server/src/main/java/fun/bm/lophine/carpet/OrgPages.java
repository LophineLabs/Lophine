package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.ref.SoftReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Vanilla custom click packets for Org's paged server text, without a client mod. */
public final class OrgPages {
    private static final Identifier TURN_PAGE = Identifier.fromNamespaceAndPath("carpet-org-addition", "turn_the_page");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, PageCache> CACHES = new ConcurrentHashMap<>();
    private record PageCache(UUID owner, java.lang.ref.WeakReference<net.minecraft.server.MinecraftServer> server,java.lang.ref.WeakReference<ServerPlayer> player, SoftReference<List<Component>> rows, int pageSize) {}
    private static final carpet.script.external.WeakIdentityMap<ServerPlayer,Boolean> EXPIRED_NOTICE=new carpet.script.external.WeakIdentityMap<>();

    private OrgPages() {}

    public static void print(CommandSourceStack source, List<Component> rows) {
        if (rows.isEmpty()) return;
        int size = Math.max(1, GeneralCompatConfig.maxLinesPerPage);
        if (source.getPlayer() == null || rows.size() <= size) {
            rows.forEach(row -> source.sendSuccess(() -> row, false));
            return;
        }
        CACHES.entrySet().removeIf(entry -> entry.getValue().rows.get() == null || entry.getValue().player.get()==null || entry.getValue().player.get().isRemoved());
        int id = NEXT_ID.getAndIncrement();
        PageCache cache = new PageCache(source.getPlayer().getUUID(),new java.lang.ref.WeakReference<>(source.getServer()),new java.lang.ref.WeakReference<>(source.getPlayer()), new SoftReference<>(rows.stream().map(Component::copy).map(value->(Component)value).toList()), size);
        CACHES.put(id, cache);
        render(source.getPlayer(), id, cache, 1,false);
    }

    public static void customClick(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!TURN_PAGE.equals(id) || payload.isEmpty() || !(payload.get() instanceof CompoundTag data)) return;
        int cacheId = data.getInt("id").orElse(-1);
        int page = data.getInt("page_number").orElse(-1);
        OrgMenuNativeEffects.run(player,()->{
            if(!acceptVersion(player,data))return null;
            PageCache cache=CACHES.get(cacheId);
            if(cache==null){player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.page.non_existent").withStyle(ChatFormatting.RED));return null;}
            if(!cache.owner.equals(player.getUUID())||cache.server.get()!=player.level().getServer())return null;
            render(player,cacheId,cache,page,true);return null;
        });
    }

    static boolean acceptVersion(ServerPlayer player,CompoundTag data){
        if(data.getInt("data_version").orElse(1)<=1)return true;
        if(EXPIRED_NOTICE.get(player)==null){EXPIRED_NOTICE.put(player,true);player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.custom_click_action.expired").withStyle(ChatFormatting.RED));}return false;
    }

    private static void render(ServerPlayer player, int id, PageCache cache, int page,boolean blank) {
        List<Component> rows = cache.rows.get();
        if (rows == null) { CACHES.remove(id, cache);player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.page.non_existent").withStyle(ChatFormatting.RED)); return; }
        int total = (rows.size() + cache.pageSize - 1) / cache.pageSize;
        if (page < 1 || page > total) {player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.page.invalid_index",page,total).withStyle(ChatFormatting.RED));return;}
        if(blank)player.sendSystemMessage(Component.empty());
        int from = (page - 1) * cache.pageSize;
        for (int index = from; index < Math.min(rows.size(), from + cache.pageSize); index++) player.sendSystemMessage(rows.get(index));
        Component counter=Component.literal("[").append(Component.literal(Integer.toString(page)).withStyle(ChatFormatting.GOLD)).append("/").append(Component.literal(Integer.toString(total)).withStyle(ChatFormatting.GOLD)).append("]").withStyle(style->style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(OrgFinderText.progress(page,total))));
        Component footer = Component.empty().append(Component.literal("  ======").withStyle(ChatFormatting.DARK_GRAY))
            .append(button(id, page - 1, page > 1, " <<< "))
            .append(" ").append(counter).append(" ")
            .append(button(id, page + 1, page < total, " >>> "))
            .append(Component.literal("======").withStyle(ChatFormatting.DARK_GRAY));
        player.sendSystemMessage(footer);
    }

    private static Component button(int id, int page, boolean enabled, String label) {
        if (!enabled) return Component.literal(label).withStyle(ChatFormatting.GRAY);
        CompoundTag data = new CompoundTag();
        data.putInt("id", id); data.putInt("page_number", page); data.putInt("data_version", 1);
        data.putInt("minecraft_data_version", SharedConstants.getCurrentVersion().dataVersion().version());
        data.putString("action_source", "CHAT");
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.Custom(TURN_PAGE, Optional.of(data)))
            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(OrgFinderText.localized("carpet-org-addition.button."+(label.contains("<<<")?"prev_page":"next_page")))));
    }
}
