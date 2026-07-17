/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.plugin.provider;

import com.destroystokyo.paper.utils.PaperPluginLogger;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContextImpl;
import io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader;
import io.papermc.paper.plugin.entrypoint.classloader.PaperSimplePluginClassLoader;
import io.papermc.paper.plugin.loader.PaperClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.provider.type.PluginTypeFactory;
import io.papermc.paper.plugin.provider.type.paper.PaperPluginParent;
import io.papermc.paper.plugin.provider.util.ProviderUtil;
import me.earthme.luminol.config.modules.unsupported.DisableCheckForFoliaSupported;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.plugin.provider.configuration.LeavesPluginMeta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public class LeavesPluginProviderFactory implements PluginTypeFactory<PaperPluginParent, LeavesPluginMeta> {
    @Override
    public PaperPluginParent build(JarFile file, LeavesPluginMeta configuration, Path source) {
        if (!configuration.isFoliaSupported() && !DisableCheckForFoliaSupported.disableForLeaves) {
            throw new RuntimeException("Could not load plugin '" + configuration.getDisplayName() + "' as it is not marked as supporting Folia!");
        }
        Logger jul = PaperPluginLogger.getLogger(configuration);
        ComponentLogger logger = ComponentLogger.logger(jul.getName());
        PluginProviderContext context = PluginProviderContextImpl.create(configuration, logger, source);

        PaperClasspathBuilder builder = new PaperClasspathBuilder(context);

        if (configuration.getLoader() != null) {
            try (
                    PaperSimplePluginClassLoader simplePluginClassLoader = new PaperSimplePluginClassLoader(source, file, configuration, this.getClass().getClassLoader())
            ) {
                PluginLoader loader = ProviderUtil.loadClass(configuration.getLoader(), PluginLoader.class, simplePluginClassLoader);
                loader.classloader(builder);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        PaperPluginClassLoader classLoader = builder.buildClassLoader(jul, source, file, configuration);
        return new PaperPluginParent(source, file, configuration, classLoader, context);
    }

    @Override
    public LeavesPluginMeta create(@NotNull JarFile file, JarEntry config) throws IOException {
        LeavesPluginMeta configuration;
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(file.getInputStream(config)))) {
            configuration = LeavesPluginMeta.create(bufferedReader);
        }
        return configuration;
    }
}