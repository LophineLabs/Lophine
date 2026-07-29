/*
 * This file is part of Kaiiju (https://github.com/KaiijuMC/Kaiiju)
 *
 * Kaiiju is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Kaiiju is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Kaiiju. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.kaiijumc.kaiiju;

import com.google.common.base.Throwables;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;

@SuppressWarnings("unused")
public class KaiijuEntityLimits {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final File CONFIG_FOLDER = new File("shiroha_config");

    protected static final String HEADER =
            "Per region entity limits for Kaiiju.\n"
                    + "If there are more of particular entity type in a region than limit, entity ticking will be throttled.\n"
                    + "Example: for Wither limit 100 & 300 Withers in a region -> 100 Withers tick every tick & every Wither ticks every 3 ticks.\n"
                    + "Entity names are named under the registry of minecraft's entity type";
    protected static final File ENTITY_LIMITS_FILE = new File(CONFIG_FOLDER, "kaiiju_entity_limits.yml");
    public static YamlConfiguration entityLimitsConfig;
    public static boolean enabled = false;

    public static void init() {
        init(true);
    }

    private static void init(boolean setup) {
        entityLimitsConfig = new YamlConfiguration();

        if (ENTITY_LIMITS_FILE.exists()) {
            try {
                entityLimitsConfig.load(ENTITY_LIMITS_FILE);
            } catch (InvalidConfigurationException ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not load kaiiju_entity_limits.yml, please correct your syntax errors", ex);
                throw Throwables.propagate(ex);
            } catch (IOException ignore) {
            }
        } else {
            if (setup) {
                entityLimitsConfig.options().header(HEADER);
                entityLimitsConfig.options().copyDefaults(true);
                entityLimitsConfig.set("enabled", enabled);
                entityLimitsConfig.set("axolotl.limit", 1000);
                entityLimitsConfig.set("axolotl.removal", 2000);

                try {
                    entityLimitsConfig.save(ENTITY_LIMITS_FILE);
                } catch (IOException ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Could not save " + ENTITY_LIMITS_FILE, ex);
                }
            }
        }

        enabled = entityLimitsConfig.getBoolean("enabled");

        if (!enabled) {
            return;
        }

        for (String key : entityLimitsConfig.getKeys(false)) {
            if (key.equals("enabled")) {
                continue;
            }

            final Optional<Holder.Reference<EntityType<?>>> lookup = BuiltInRegistries.ENTITY_TYPE.get(Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, key));
            if (lookup.isEmpty()) {
                LOGGER.error("Unknown entity '{}' in kaiiju-entity-limits.yml, skipping", key);
                continue;
            }

            final EntityType<?> value = lookup.get().value();

            int limit = entityLimitsConfig.getInt(key + ".limit");
            int removal = entityLimitsConfig.getInt(key + ".removal");

            if (limit < 1) {
                LOGGER.error("{} has a limit less than the minimum of 1, ignoring", key);
                continue;
            }
            if (removal <= limit && removal != -1) {
                LOGGER.error("{} has a removal limit that is less than or equal to its limit, setting removal to limit * 10", key);
                removal = limit * 10;
            }

            value.entityLimit = new EntityLimit(limit, removal);
        }
    }

    public record EntityLimit(int limit, int removal) {
        @Override
        public @NonNull String toString() {
            return "EntityLimit{limit=" + limit + ", removal=" + removal + "}";
        }
    }
}
