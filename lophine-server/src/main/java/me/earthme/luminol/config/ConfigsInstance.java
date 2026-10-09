package me.earthme.luminol.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.utils.ServerI18nUtil;
import io.papermc.paper.threadedregions.RegionizedServer;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import me.earthme.luminol.api.config.EnumConfigData;
import me.earthme.luminol.api.config.LuminolConfigsInstance;
import me.earthme.luminol.commands.config.ConfigCommand;
import me.earthme.luminol.config.flags.*;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;
import me.earthme.luminol.enums.EnumRunnableType;
import me.earthme.luminol.utils.ClassLoadUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Configuration instance manager that handles loading, reloading and managing configuration modules.
 */
public class ConfigsInstance implements LuminolConfigsInstance {
    public final Logger logger = LogUtils.getClassLogger();

    // Basic configuration properties
    private final ClassLoader loader;
    private final File baseConfigFolder;
    private final File baseConfigFile;
    private final String name;          // Used to transform config to another config system
    private final String commandName;   // Used to register command
    private final String pack;          // Used to find all classes

    // Storage collections
    private final Map<Object, Set<Exception>> allInstanced = new HashMap<>(); // add exception to map to store exceptions
    private final Map<String, Object> stagedConfigMap = new HashMap<>();
    private final Map<String, Object> defaultvalueMap = new HashMap<>();
    private final Map<Integer, String> uniqueIdMap = new Int2ObjectArrayMap<>();
    private final Map<String, String[]> suggestionsMap = new HashMap<>();

    // Constants and state flags
    public boolean alreadyInit = false;
    private CommentedFileConfig configFileInstance = null;

    /**
     * Private constructor to create a configuration instance
     */
    protected ConfigsInstance(@NotNull ClassLoader loader, @NotNull File base, @NotNull String name, @NotNull String file_name,
                              @NotNull String command_name, @NotNull String pack) {
        this.loader = loader;
        this.baseConfigFolder = base;
        this.name = name;
        this.pack = pack;
        this.commandName = command_name;
        this.baseConfigFile = new File(base, file_name);
    }

    // Lifecycle management methods
    // ========================================================================

    /**
     * Setup the configuration latch by registering the command
     */
    public void setupLatch() {
        ConfigCommand command = new ConfigCommand(name, commandName, this);
        command.register();
        alreadyInit = true;
    }

    /**
     * Reload configuration with default settings (keeping comments)
     */
    public void reload() {
        reload(true);
    }

    /**
     * Reload configuration with option to keep comments
     */
    public void reload(boolean keepComments) {
        reload(keepComments, true);
    }

    public void reload(boolean keepComments, boolean needGlobal) {
        if (needGlobal) {
            RegionizedServer.ensureGlobalTickThread("Reload " + baseConfigFile.getName() + " off global region thread!");
        }
        Map<String, Object> previousCarpet = this.name.equals("lophine_carpet")
                ? fun.bm.lophine.carpet.CarpetConfigLifecycle.snapshot() : null;
        runUnloadTasks();
        dropAllInstanced();
        try {
            preLoadConfig(keepComments);
            finalizeLoadConfig();
            if (previousCarpet != null) fun.bm.lophine.carpet.CarpetConfigLifecycle.reloaded(previousCarpet);
        } catch (Exception e) {
            logger.error("Fail to load config file of {}.", name, e);
        }
    }

    /**
     * Initialize configuration if this config not register in server load stage
     */
    @Override
    public void initialize() throws IOException {
        preLoadConfig(true);
        ConfigManager.acceptTransformedConfigs();
        ConfigManager.runTaskBeforeFinalLoad();
        finalizeLoadConfig();
    }

    /**
     * Reload configuration asynchronously
     */
    public @NotNull CompletableFuture<Void> reloadAsync(boolean keepComments) {
        return CompletableFuture.runAsync(() -> reload(keepComments), task -> RegionizedServer.getInstance().addTask(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("Fail to reload config of {}", name, e);
            }
        }));
    }

    /**
     * Clear all instantiated modules
     */
    public void dropAllInstanced() {
        allInstanced.clear();
    }

    /**
     * Run unload tasks for all modules
     */
    public void runUnloadTasks() {
        for (Object module : allInstanced.keySet()) {
            invokeNeedRunMethods(module, EnumRunnableType.ON_UNLOAD, null);
        }
    }

    /**
     * Finalize configuration loading by calling @NeedRun(ON_LOADED) methods for all modules
     */
    public void finalizeLoadConfig() {
        for (Map.Entry<Object, Set<Exception>> entry : allInstanced.entrySet()) {
            invokeNeedRunMethods(entry.getKey(), EnumRunnableType.ON_LOADED, entry.getValue());
        }
        // if the config load with exceptions but allowed, remove exceptions from the map
        allInstanced.replaceAll((_, _) -> null);
        setupLatch();
        saveConfigs();
    }

    /**
     * Re-apply staged configuration values to module fields after beforeFinalLoad tasks
     * This ensures that any config changes made in beforeFinalLoad are properly reflected
     */
    public void reApplyStagedConfigs() {
        if (stagedConfigMap.isEmpty()) {
            return;
        }

        // For each staged config value, update the corresponding field
        for (Map.Entry<Object, Set<Exception>> entry : allInstanced.entrySet()) {
            Object module = entry.getKey();

            Field[] fields = module.getClass().getDeclaredFields();

            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (!(Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers))) {
                    continue;
                }

                ConfigInfo configInfo = field.getAnnotation(ConfigInfo.class);
                DoNotLoad doNotLoad = field.getAnnotation(DoNotLoad.class);
                if (configInfo == null || (doNotLoad != null && doNotLoad.when() == EnumLoadType.ALWAYS)) {
                    continue;
                }

                // Build full configuration key
                ConfigClassInfo classInfo = getConfigClassInfo(module);
                if (classInfo == null) continue;

                final String fullConfigKeyName = ConfigPaths.resolve(classInfo, configInfo);

                // Check if this config has a staged value
                if (stagedConfigMap.containsKey(fullConfigKeyName)) {
                    try {
                        field.setAccessible(true);
                        Object stagedValue = getActualConfigValue(fullConfigKeyName);
                        if (stagedValue != null) {
                            stagedValue = tryTransform(field, stagedValue);
                            field.set(null, stagedValue);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to re-apply staged config value for {}", fullConfigKeyName, e);
                    }
                }
            }
        }
    }

    // Configuration loading methods
    // ========================================================================

    /**
     * Preload configuration with default settings (keeping comments)
     */
    public void preLoadConfig() throws IOException {
        preLoadConfig(true);
    }

    /**
     * Preload configuration with option to keep comments
     */
    public void preLoadConfig(boolean keepComments) throws IOException {
        baseConfigFolder.mkdirs();

        if (!baseConfigFile.exists()) {
            baseConfigFile.createNewFile();
        }
        if (configFileInstance != null) {
            configFileInstance.close();
        }

        // Carpet setDefault completes only after the real write or its exception.
        configFileInstance = this.name.equals("lophine_carpet")
                ? CommentedFileConfig.builder(baseConfigFile).sync().build() : CommentedFileConfig.of(baseConfigFile);
        configFileInstance.load();

        try {
            instanceAllModule();
            migrateConfigSections();
            if (this.name.equals("lophine_carpet")) {
                try (var view = fun.bm.lophine.carpet.CarpetRuleRegistry.configurationView(this::peekCarpetConfigValue)) {
                    loadAllModules(keepComments);
                }
            } else loadAllModules(keepComments);
        } catch (Exception e) {
            logger.error("Failed to load config modules!", e);
            throw new RuntimeException(e);
        }

        saveConfigs();
    }

    /**
     * Move old section keys before Carpet validates the complete requested rule set.
     */
    private void migrateConfigSections() {
        for (Object module : allInstanced.keySet()) {
            ConfigClassInfo classInfo = getConfigClassInfo(module);
            for (Field field : module.getClass().getDeclaredFields()) {
                ConfigInfo info = field.getAnnotation(ConfigInfo.class);
                if (info == null || info.section().isEmpty() || !Modifier.isStatic(field.getModifiers())
                        || Modifier.isFinal(field.getModifiers()) || field.getAnnotation(DoNotLoad.class) != null)
                    continue;
                String legacy = ConfigPaths.legacy(classInfo, info);
                String target = ConfigPaths.resolve(classInfo, info);
                if (legacy.equals(target) || !configFileInstance.contains(legacy)) continue;
                if (!configFileInstance.contains(target)) {
                    configFileInstance.set(target, configFileInstance.get(legacy));
                    String comment = configFileInstance.getComment(legacy);
                    if (comment != null) configFileInstance.setComment(target, comment);
                }
                configFileInstance.remove(legacy);
                List<String> parents = new ArrayList<>(List.of(legacy.split("\\.")));
                parents.removeLast();
                while (!parents.isEmpty()) {
                    String parent = String.join(".", parents);
                    Object value = configFileInstance.get(parent);
                    if (!(value instanceof UnmodifiableConfig table) || !table.isEmpty()) break;
                    configFileInstance.remove(parent);
                    parents.removeLast();
                }
            }
        }
    }

    /**
     * Load all configuration modules
     */
    private void loadAllModules(boolean keepComments) {
        if (alreadyInit && this.name.equals("lophine_carpet")) restoreCarpetDefaults();
        Map<Object, Set<Exception>> stagedMap = new HashMap<>();
        for (Object instanced : allInstanced.keySet()) {
            Set<Exception> exceptions = loadForSingle(instanced, keepComments);
            if (exceptions != null) {
                stagedMap.put(instanced, exceptions);
            }
        }

        allInstanced.putAll(stagedMap);
        if (this.name.equals("lophine_carpet")) {
            for (Object module : allInstanced.keySet()) {
                Runnable before = () -> invokeNeedRunMethods(module, EnumRunnableType.BEFORE_FINAL_LOAD, null);
                if (alreadyInit) before.run();
                else ConfigManager.registerRunnableBeforeFinalLoad(before);
            }
        }
    }

    /**
     * Instantiate all configuration modules
     */
    private void instanceAllModule() throws NoSuchMethodException, InvocationTargetException,
            InstantiationException, IllegalAccessException {
        for (Class<?> clazz : ClassLoadUtil.getClasses(pack, loader)) {
            ConfigClassInfo configClassInfo = getConfigClassInfo(clazz);
            if (configClassInfo != null) {
                allInstanced.put(clazz.getConstructor().newInstance(), null);
            }
        }
    }

    /**
     * Load configuration for a single module
     */
    private @Nullable Set<Exception> loadForSingle(@NotNull Object singleConfigModule, boolean keepComments) {
        ConfigClassInfo configClassInfo = getConfigClassInfo(singleConfigModule);

        // Build configuration path and handle class comments
        final List<String> category = buildConfigCategoryPath(configClassInfo);
        final String fullConfigBasePath = String.join(".", category);
        handleClassLevelComments(fullConfigBasePath, keepComments);

        // Process each field in the module
        Field[] fields = singleConfigModule.getClass().getDeclaredFields();
        Set<Exception> exception = new HashSet<>();
        for (Field field : fields) {
            try {
                processConfigField(field, singleConfigModule, keepComments);
            } catch (Exception e) {
                exception.add(e);
            }
        }
        return exception.isEmpty() ? null : exception;
    }

    /**
     * Get ConfigClassInfo annotation from a config module
     */
    private ConfigClassInfo getConfigClassInfo(Object singleConfigModule) {
        Class<?> clazz = singleConfigModule instanceof Class<?> ? (Class<?>) singleConfigModule : singleConfigModule.getClass();
        return clazz.getAnnotation(ConfigClassInfo.class);
    }

    /**
     * Build configuration category path from ConfigClassInfo annotation
     */
    private List<String> buildConfigCategoryPath(ConfigClassInfo configClassInfo) {
        final List<String> category = new ArrayList<>();
        String name = configClassInfo.category().getBaseKeyName();
        if (name != null) category.add(name);
        category.addAll(List.of(configClassInfo.directory()));
        category.add(configClassInfo.name());
        return category;
    }

    /**
     * Handle class-level comments for configuration
     */
    private void handleClassLevelComments(String fullConfigBasePath, boolean keepComments) {
        final String existingComment = configFileInstance.getComment(fullConfigBasePath);
        final String localizedComment = ServerI18nUtil.getLocalizedText(name + "." + fullConfigBasePath + ".comment");
        if (!keepComments) {
            // Force reset to localized default
            if (!localizedComment.isBlank()) {
                configFileInstance.setComment(fullConfigBasePath, localizedComment);
            }
        } else if (existingComment == null || existingComment.isBlank()) {
            // Only fill in when blank
            if (!localizedComment.isBlank()) {
                configFileInstance.setComment(fullConfigBasePath, localizedComment);
            }
        }
    }

    /**
     * Process a single configuration field
     */
    private void processConfigField(Field field, @NotNull Object singleConfigModule,
                                    boolean keepComments) throws IllegalAccessException {
        int modifiers = field.getModifiers();
        if (!(Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers))) {
            return;
        }

        // Check for special annotations
        DoNotLoad doNotLoad = field.getAnnotation(DoNotLoad.class);
        boolean skipLoad = doNotLoad != null && doNotLoad.when() == EnumLoadType.ALWAYS;
        boolean doNotReload = alreadyInit && doNotLoad != null && doNotLoad.when() == EnumLoadType.RELOAD;
        ConfigInfo configInfo = field.getAnnotation(ConfigInfo.class);

        if (configInfo == null) return;

        // Build full configuration key
        ConfigClassInfo moduleInfo = getConfigClassInfo(singleConfigModule);
        final String fullConfigKeyName = ConfigPaths.resolve(moduleInfo, configInfo);
        if (!configInfo.section().isEmpty()) {
            handleClassLevelComments(String.join(".", ConfigPaths.section(moduleInfo, configInfo)), keepComments);
        }

        if (!alreadyInit) {
            uniqueIdMap.put(uniqueIdMap.size(), fullConfigKeyName);
        }

        if (skipLoad) return;

        field.setAccessible(true);
        Object currentValue = field.get(null);
        if (currentValue instanceof Enum) {
            currentValue = ((Enum<?>) currentValue).name();
        }
        boolean removed = getConfigClassInfo(singleConfigModule).category() == EnumConfigCategory.REMOVED;

        // Store default value if not initialized
        if (!alreadyInit && !removed) {
            defaultvalueMap.put(fullConfigKeyName, currentValue);
        }

        // Handle missing or removed configurations
        if (!configFileInstance.contains(fullConfigKeyName) || removed) {
            handleMissingOrRemovedConfig(field, fullConfigKeyName, configInfo, removed, doNotReload);
        } else {
            // Handle existing configurations
            handleExistingConfig(field, fullConfigKeyName, configInfo, doNotReload, keepComments);
        }

        // handle tasks need processed before config finalized
        if (!alreadyInit && !this.name.equals("lophine_carpet")) {
            for (Method method : singleConfigModule.getClass().getDeclaredMethods()) {
                NeedRun needRun = method.getAnnotation(NeedRun.class);
                if (needRun != null && needRun.when() == EnumRunnableType.BEFORE_FINAL_LOAD) {
                    method.setAccessible(true);
                    ConfigManager.registerRunnableBeforeFinalLoad(() ->
                            invokeNeedRunMethods(singleConfigModule, EnumRunnableType.BEFORE_FINAL_LOAD, null)
                    );
                }
            }
        }
    }

    /**
     * Handle missing or removed configuration entries
     */
    private void handleMissingOrRemovedConfig(Field field, String fullConfigKeyName,
                                              ConfigInfo configInfo, boolean removed, boolean doNotReload) throws IllegalAccessException {
        // Process transformed configurations
        processTransformedConfigs(field, fullConfigKeyName, removed);

        // Handle removed configurations
        if (removed) {
            configFileInstance.remove("removed");
            return;
        }

        // Skip if value already exists
        if (configFileInstance.get(fullConfigKeyName) != null) {
            return;
        }

        // Validate default value
        Object currentValue = field.get(null);
        if (alreadyInit && this.name.equals("lophine_carpet") && defaultvalueMap.containsKey(fullConfigKeyName)) {
            currentValue = tryTransform(field, defaultvalueMap.get(fullConfigKeyName));
            if (!doNotReload) field.set(null, currentValue);
        }
        if (currentValue instanceof Enum) {
            currentValue = ((Enum<?>) currentValue).name();
        } else if (currentValue instanceof List<?> list) {
            if (!list.isEmpty() && list.getFirst() instanceof Enum<?>) {
                List<String> stringList = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Enum<?> e) {
                        stringList.add(e.name());
                    } else {
                        stringList.add(item.toString());
                    }
                }
                currentValue = stringList;
            }
        }
        if (currentValue == null) {
            throw new UnsupportedOperationException("Config " + configInfo.name() + "tried to add an null default value!");
        }

        // Add default value with comments
        final String comments = ServerI18nUtil.getLocalizedText(name + "." + fullConfigKeyName + ".comment");
        if (!comments.isBlank()) {
            configFileInstance.setComment(fullConfigKeyName, comments);
        }

        configFileInstance.add(fullConfigKeyName, currentValue);
    }

    /**
     * Process transformed configurations
     */
    private void processTransformedConfigs(Field field, String fullConfigKeyName, boolean removed) {
        for (TransformedConfig transformedConfig : field.getAnnotationsByType(TransformedConfig.class)) {
            final String oldConfigKeyName = String.join(".", transformedConfig.directory()) + "." + transformedConfig.name();

            if (!(Objects.equals(transformedConfig.originInstance(), "") || Objects.equals(transformedConfig.originInstance(), name))) {
                ConfigManager.registerTransformedConfig(transformedConfig.originInstance(), name,
                        oldConfigKeyName, fullConfigKeyName, transformedConfig);
            } else {
                Object oldValue = configFileInstance.get(oldConfigKeyName);
                if (oldValue != null) {
                    boolean success = true;

                    if (transformedConfig.transform() && !removed) {
                        try {
                            for (Class<? extends DefaultTransformLogic> logic : transformedConfig.transformLogic()) {
                                oldValue = logic.getDeclaredConstructor().newInstance().transform(oldValue);
                            }
                            configFileInstance.set(fullConfigKeyName, oldValue);
                        } catch (Exception e) {
                            success = false;
                            logger.error("Failed to transform removed config {}!", transformedConfig.name());
                        }

                        if (transformedConfig.transformComments()) {
                            configFileInstance.setComment(fullConfigKeyName,
                                    configFileInstance.getComment(oldConfigKeyName));
                        }
                    }

                    if (success) {
                        removeConfig(oldConfigKeyName, transformedConfig.directory());
                    }

                    final String comments = ServerI18nUtil.getLocalizedText(name + "." + fullConfigKeyName + ".comment");
                    if (!comments.isBlank()) {
                        configFileInstance.setComment(fullConfigKeyName, comments);
                    }

                    if (!removed && configFileInstance.get(fullConfigKeyName) != null) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Handle existing configuration entries
     */
    private void handleExistingConfig(Field field, String fullConfigKeyName,
                                      ConfigInfo configInfo, boolean doNotReload,
                                      boolean keepComments) throws IllegalAccessException, IllegalFormatConversionException {
        // Handle existing configurations
        Object actuallyValue = getActualConfigValue(fullConfigKeyName, field);

        IllegalFormatConversionException e0 = null;

        // Transform value if needed
        try {
            actuallyValue = tryTransform(field, actuallyValue);
            configFileInstance.set(fullConfigKeyName, actuallyValue);
        } catch (IllegalFormatConversionException e) {
            if (configInfo.allowAutoReset()) {
                actuallyValue = tryTransform(field, defaultvalueMap.get(fullConfigKeyName));
                e0 = e;
                logger.error("Failed to transform config {}, because of annotation of allowAutoReset, reset to default!", fullConfigKeyName);
            } else {
                logger.error("Failed to transform config {}, because of annotation of allowAutoReset, keep old value!", fullConfigKeyName);
                throw e;
            }
        }

        // Update field value if hot reload is supported
        if (!doNotReload) {
            field.set(null, actuallyValue);
        }

        // Handle comments
        if (!keepComments) {
            final String comments = ServerI18nUtil.getLocalizedText(name + "." + fullConfigKeyName + ".comment");
            configFileInstance.setComment(fullConfigKeyName, comments);
        }

        // Store suggestions for command completion
        if (!alreadyInit) {
            CommandSuggestions commandSuggestions = field.getAnnotation(CommandSuggestions.class);
            if (commandSuggestions != null) {
                suggestionsMap.put(fullConfigKeyName, commandSuggestions.suggest());
            }
        }
        if (e0 != null) throw e0;
    }

    /**
     * Get the actual configuration value, handling staged values
     */
    /**
     * Read the complete requested file without consuming staged values during cross-rule validation.
     */
    private Object peekCarpetConfigValue(String fullConfigKeyName) {
        Object value = stagedConfigMap.containsKey(fullConfigKeyName)
                ? stagedConfigMap.get(fullConfigKeyName) : configFileInstance.get(fullConfigKeyName);
        if (value == null) value = defaultvalueMap.get(fullConfigKeyName);
        return value instanceof String text ? parseListFromString(text) : value;
    }

    /**
     * Runtime overrides are cleared before reading a new Carpet file, including invalid or missing entries.
     */
    private void restoreCarpetDefaults() {
        for (Object module : allInstanced.keySet()) {
            ConfigClassInfo classInfo = getConfigClassInfo(module);
            for (Field field : module.getClass().getDeclaredFields()) {
                ConfigInfo info = field.getAnnotation(ConfigInfo.class);
                DoNotLoad loading = field.getAnnotation(DoNotLoad.class);
                if (info == null || !Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                        || loading != null) continue;
                String key = ConfigPaths.resolve(classInfo, info);
                if (!defaultvalueMap.containsKey(key)) continue;
                try {
                    field.setAccessible(true);
                    field.set(null, tryTransform(field, defaultvalueMap.get(key)));
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("Cannot restore Carpet rule " + key, failure);
                }
            }
        }
    }

    private Object getActualConfigValue(String fullConfigKeyName) {
        Object actuallyValue;
        if (stagedConfigMap.containsKey(fullConfigKeyName)) {
            actuallyValue = stagedConfigMap.get(fullConfigKeyName);
            if (actuallyValue == null) {
                actuallyValue = defaultvalueMap.get(fullConfigKeyName);
            }
            if (actuallyValue instanceof String v) {
                actuallyValue = parseListFromString(v);
            }
            stagedConfigMap.remove(fullConfigKeyName);
        } else {
            actuallyValue = configFileInstance.get(fullConfigKeyName);
        }
        return actuallyValue;
    }

    private Object getActualConfigValue(String fullConfigKeyName, Field field) {
        Object value = getActualConfigValue(fullConfigKeyName);
        if (field != null && value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String) {
            Class<?> elementType = getListElementType(field);
            if (elementType != null && elementType.isEnum()) {
                List<Object> transformedList = new ArrayList<>();
                for (Object item : list) {
                    String enumValue = item.toString();
                    Object enumConstant = Arrays.stream(elementType.getEnumConstants())
                            .filter(e -> ((Enum<?>) e).name().equalsIgnoreCase(enumValue))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No enum constant " + elementType.getSimpleName() + "." + enumValue));
                    transformedList.add(enumConstant);
                }
                return Collections.unmodifiableList(transformedList);
            }
        }
        return value;
    }

    // Configuration manipulation methods
    // ========================================================================

    /**
     * Remove a configuration entry
     */
    public void removeConfig(String name, String[] keys) {
        configFileInstance.remove(name);
        Object configAtPath = configFileInstance.get(String.join(".", keys));
        if (configAtPath instanceof UnmodifiableConfig && ((UnmodifiableConfig) configAtPath).isEmpty() || configAtPath == null) {
            removeConfig(keys);
        }
    }

    /**
     * Remove a configuration entry
     */
    public void removeConfig(String key) {
        // split on literal dot to get path segments
        removeConfig(key, key.split("\\."));
    }

    /**
     * Recursively remove configuration entries
     */
    private void removeConfig(String[] keys) {
        configFileInstance.remove(String.join(".", keys));
        Object configAtPath = configFileInstance.get(String.join(".", Arrays.copyOfRange(keys, 1, keys.length)));
        if (configAtPath instanceof UnmodifiableConfig && ((UnmodifiableConfig) configAtPath).isEmpty()) {
            removeConfig(Arrays.copyOfRange(keys, 1, keys.length));
        }
    }

    /**
     * Set configuration value by keys array
     */
    public boolean setConfig(String[] keys, Object value) {
        return setConfig(String.join(".", keys), value);
    }

    /**
     * Parse a list from string representation
     */
    public Object parseListFromString(String input) {
        if (input.startsWith("[") && input.endsWith("]")) {
            String content = input.substring(1, input.length() - 1).trim();

            if (content.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            boolean escapeNext = false;

            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);

                if (escapeNext) {
                    current.append(c);
                    escapeNext = false;
                } else if (c == '\\') {
                    escapeNext = true;
                } else if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == ',' && !inQuotes) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }

            if (!current.isEmpty()) {
                result.add(current.toString().trim());
            }

            return result.stream().map(s -> {
                if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                    return s.substring(1, s.length() - 1);
                }
                return s;
            }).collect(Collectors.toList());
        }
        return input;
    }

    /**
     * Convert a list to its string representation
     */
    public String parseStringFromList(List<?> list) {
        String ret;
        if (list.isEmpty()) {
            return "[]";
        }
        if (list.getFirst() instanceof String) {
            ret = list.stream()
                    .map(obj -> {
                        String str = obj.toString();
                        if (str.contains(",") || str.contains("\"") || str.contains(" ") || str.contains("[")) {
                            str = str.replace("\"", "\\\"");
                            return "\"" + str + "\"";
                        }
                        return str;
                    })
                    .collect(Collectors.joining(", ", "[", "]"));
        } else if (list.getFirst() instanceof Number) {
            ret = list.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "[", "]"));
        } else if (list.getFirst() instanceof Enum<?>) {
            ret = list.stream()
                    .map(obj -> {
                        String str = ((Enum<?>) obj).name();
                        return "\"" + str + "\"";
                    })
                    .collect(Collectors.joining(", ", "[", "]"));
        } else {
            ret = list.stream()
                    .map(obj -> {
                        String str;
                        try {
                            str = (String) list.getFirst().getClass().getMethod("transformInList").invoke(obj);
                        } catch (Exception e) {
                            str = null;
                        }
                        return "\"" + str + "\"";
                    })
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return ret;
    }

    /**
     * Set configuration value by key
     */
    public boolean setConfig(String key, Object value) {
        if (configFileInstance.contains(key) && configFileInstance.get(key) != null) {
            stagedConfigMap.put(key, value);
            return true;
        }
        return false;
    }

    public enum SingleConfigResult {
        UPDATED, UNCHANGED, SAVED_FOR_RESTART, UNKNOWN_KEY
    }

    public synchronized SingleConfigResult applySingleConfig(final String key, final Object value, final boolean persist) throws IllegalAccessException {
        for (Object module : allInstanced.keySet()) {
            ConfigClassInfo classInfo = getConfigClassInfo(module);
            if (classInfo == null) continue;
            for (Field field : module.getClass().getDeclaredFields()) {
                ConfigInfo info = field.getAnnotation(ConfigInfo.class);
                if (info == null || !Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                    continue;
                if (!ConfigPaths.resolve(classInfo, info).equals(key)) continue;
                DoNotLoad loading = field.getAnnotation(DoNotLoad.class);
                if (loading != null && loading.when() == EnumLoadType.ALWAYS) return SingleConfigResult.UNKNOWN_KEY;
                field.setAccessible(true);
                Object converted = tryTransform(field, value);
                if (loading != null && loading.when() == EnumLoadType.RELOAD) {
                    if (!persist)
                        throw new IllegalStateException("This rule requires a server restart; use setDefault to save it.");
                    configFileInstance.set(key, converted);
                    stagedConfigMap.remove(key);
                    saveConfigs();
                    return SingleConfigResult.SAVED_FOR_RESTART;
                }
                boolean changed = !Objects.equals(field.get(null), converted);
                if (changed) {
                    invokeNeedRunMethods(module, EnumRunnableType.ON_UNLOAD, null);
                    field.set(null, converted);
                    stagedConfigMap.remove(key);
                    invokeNeedRunMethods(module, EnumRunnableType.BEFORE_FINAL_LOAD, null);
                    invokeNeedRunMethods(module, EnumRunnableType.ON_LOADED, null);
                }
                stagedConfigMap.remove(key);
                if (persist) {
                    configFileInstance.set(key, converted);
                    saveConfigs();
                }
                return changed ? SingleConfigResult.UPDATED : SingleConfigResult.UNCHANGED;
            }
        }
        return SingleConfigResult.UNKNOWN_KEY;
    }

    /**
     * Attempt to transform a value to target type
     */
    private Object tryTransform(Field field, Object value) throws IllegalAccessException {
        if (field.getDeclaringClass().getPackageName().equals("fun.bm.lophine.carpet.config.modules")) {
            try {
                String text = value instanceof List<?> list ? String.join(",", list.stream().map(Object::toString).toList()) : value.toString();
                ConfigInfo info = field.getAnnotation(ConfigInfo.class);
                String upstreamType = fun.bm.lophine.carpet.CarpetRuleMetadata.get(info.name()).type();
                if (field.getType() == String.class && !Set.of("", "String", "boolean", "int", "long", "double", "float").contains(upstreamType))
                    text = text.toLowerCase(Locale.ROOT);
                return fun.bm.lophine.carpet.CarpetRuleRegistry.get(info.name()).parse(text);
            } catch (RuntimeException failure) {
                logger.error("Invalid Carpet rule {}: {}", field.getName(), failure.getMessage());
                throw new IllegalFormatConversionExceptionWithOrigin((char) 0, field.getType(), value);
            }
        }
        Class<?> targetType = field.get(null).getClass();
        if (!targetType.isAssignableFrom(value.getClass())) {
            try {
                if (targetType == Integer.class) {
                    value = Integer.parseInt(value.toString());
                } else if (targetType == Double.class) {
                    value = Double.parseDouble(value.toString());
                } else if (targetType == Boolean.class) {
                    value = Boolean.parseBoolean(value.toString());
                } else if (targetType == Long.class) {
                    value = Long.parseLong(value.toString());
                } else if (targetType == Float.class) {
                    value = Float.parseFloat(value.toString());
                } else if (targetType == String.class) {
                    value = value.toString();
                } else if (targetType.isEnum()) {
                    String enumValue = value.toString();
                    // ignore case to match enum
                    value = Arrays.stream(targetType.getEnumConstants())
                            .filter(e -> ((Enum<?>) e).name().equalsIgnoreCase(enumValue))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No enum constant " + targetType.getSimpleName() + "." + enumValue));
                } else if (List.class.isAssignableFrom(targetType) && value instanceof List<?> valueList) {
                    if (!valueList.isEmpty() && valueList.getFirst() instanceof String) {
                        Class<?> elementType = getListElementType(field);

                        if (elementType != null && elementType.isEnum()) {
                            List<Object> transformedList = new ArrayList<>();
                            for (Object item : valueList) {
                                String enumValue = item.toString();
                                Object enumConstant = Arrays.stream(elementType.getEnumConstants())
                                        .filter(e -> ((Enum<?>) e).name().equalsIgnoreCase(enumValue))
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException("No enum constant " + elementType.getSimpleName() + "." + enumValue));
                                transformedList.add(enumConstant);
                            }
                            value = Collections.unmodifiableList(transformedList);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to transform value {}!", value);
                throw new IllegalFormatConversionExceptionWithOrigin((char) 0, targetType, value);
            }
        }
        return value;
    }

    private Class<?> getListElementType(Field field) {
        java.lang.reflect.Type genericType = field.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType parameterizedType) {
            java.lang.reflect.Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return null;
    }

    /**
     * Save configuration to file
     */
    public void saveConfigs() {
        configFileInstance.save();
    }

    /**
     * Reset configuration by key
     */
    public void resetConfig(String key) {
        stagedConfigMap.put(key, null);
    }

    // Configuration retrieval methods
    // ========================================================================

    /**
     * Get default configuration value as string
     */
    public Object getDefaultConfig(String key) {
        return defaultvalueMap.get(key);
    }

    /**
     * Get configuration value as string by key
     */
    public String getConfig(String key) {
        return getConfigOrigin(key).toString();
    }

    /**
     * Get original configuration value by key
     */
    public <T> T getConfigOrigin(String key) {
        return configFileInstance.get(key);
    }

    /**
     * Get configuration suggestions by key
     */
    public String[] getConfigSuggestions(String key) {
        return suggestionsMap.get(key);
    }

    /**
     * Get the underlying configuration file instance
     */
    public CommentedFileConfig getFileInstance() {
        return configFileInstance;
    }

    /**
     * Get the name of the configuration instance
     */
    public String getName() {
        return name;
    }

    // Configuration path completion methods
    // ========================================================================

    /**
     * Complete configuration path based on partial path
     */
    public List<String> completeConfigPath(String partialPath) {
        List<String> allPaths = getAllConfigPaths(partialPath);
        List<String> result = new ArrayList<>();

        for (String path : allPaths) {
            String remaining = path.substring(partialPath.length());
            if (remaining.isEmpty()) continue;

            int dotIndex = remaining.indexOf('.');
            String suggestion = (dotIndex == -1)
                    ? path
                    : partialPath + remaining.substring(0, dotIndex);

            if (!result.contains(suggestion)) {
                result.add(suggestion);
            }
        }
        return result;
    }

    /**
     * Get single configuration entries under a key
     */
    public List<String> getSingleConfig(String key) {
        List<String> list = new ArrayList<>();
        if (!key.endsWith(".")) {
            key += ".";
        }
        List<String> checkList = completeConfigPath(key);
        for (String check : checkList) {
            if (completeConfigPath(check + ".").isEmpty()) {
                list.add(check);
            }
        }
        return list;
    }

    /**
     * Get all configuration paths starting with current path
     */
    public List<String> getAllConfigPaths(String currentPath) {
        return defaultvalueMap.keySet().stream()
                .filter(k -> k.startsWith(currentPath))
                .toList();
    }

    public String getConfigPathById(int id) {
        return uniqueIdMap.get(id);
    }

    // Data retrieval methods
    // ========================================================================

    /**
     * Get configuration data
     */
    public Map<String, Map<EnumConfigData, Object>> getData(Collection<String> list, Collection<EnumConfigData> features) {
        Map<String, Map<EnumConfigData, Object>> map = new TreeMap<>();
        for (String key : list) {
            Map<EnumConfigData, Object> dataMap = new EnumMap<>(EnumConfigData.class);
            for (EnumConfigData feature : features) {
                switch (feature) {
                    case EnumConfigData.REAL_VALUE -> dataMap.put(feature, configFileInstance.get(key));
                    case EnumConfigData.VALUE -> dataMap.put(feature, parseDataToReadable(configFileInstance.get(key)));
                    case EnumConfigData.STRING_VALUE ->
                            dataMap.put(feature, parseDataToReadable(configFileInstance.get(key)).toString());
                    case EnumConfigData.COMMENT -> {
                        String comment = configFileInstance.getComment(key);
                        if (comment == null || comment.isEmpty()) {
                            comment = null;
                        }
                        dataMap.put(feature, comment);
                    }
                    case EnumConfigData.SUGGESTIONS -> {
                        String[] suggestions = getConfigSuggestions(key);
                        if (suggestions == null) {
                            if (configFileInstance.get(key) instanceof Enum<?> enumValue) {
                                Enum<?>[] values = enumValue.getClass().getEnumConstants();
                                suggestions = new String[values.length];
                                for (Enum<?> enumValue1 : values) {
                                    suggestions[enumValue1.ordinal()] = enumValue1.name();
                                }
                            }
                        }
                        dataMap.put(feature, suggestions);
                    }
                    case EnumConfigData.LOCALIZED_NAME -> {
                        String name = ServerI18nUtil.getLocalizedText(getName() + "." + key);
                        if (name.isEmpty()) {
                            name = ServerI18nUtil.getFormatedLocalizedTextOrDefault("general." + key, key);
                        }
                        dataMap.put(feature, name);
                    }
                    case EnumConfigData.UNIQUE_ID -> {
                        for (int i = 0; i < uniqueIdMap.size(); i++) {
                            if (uniqueIdMap.get(i).equals(key)) {
                                dataMap.put(feature, i);
                                break;
                            }
                        }
                    }
                }
            }
            map.put(key, dataMap);
        }
        return map;
    }

    private Object parseDataToReadable(Object value) {
        if (value instanceof List<?> list1) {
            return parseStringFromList(list1);
        } else if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return value;
    }

    /**
     * Clean up unused configuration entries
     */
    public void clean() {
        Map<String, Object> validValues = new HashMap<>();
        Map<String, String> validComments = new HashMap<>();
        for (String key : defaultvalueMap.keySet()) {
            validValues.put(key, configFileInstance.get(key));
            validComments.put(key, configFileInstance.getComment(key));
        }
        configFileInstance.clear();
        validValues.forEach(configFileInstance::set);
        validComments.forEach(configFileInstance::setComment);
        saveConfigs();
    }

    private void invokeNeedRunMethods(Object module, EnumRunnableType type, @Nullable Set<Exception> exs) {
        for (Method method : module.getClass().getDeclaredMethods()) {
            NeedRun needRun = method.getAnnotation(NeedRun.class);
            if (needRun != null && needRun.when() == type) {
                try {
                    method.setAccessible(true);
                    Class<?>[] paramTypes = method.getParameterTypes();
                    Object[] args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (CommentedFileConfig.class.isAssignableFrom(paramTypes[i])) {
                            args[i] = configFileInstance;
                        } else if (Set.class.isAssignableFrom(paramTypes[i])) {
                            args[i] = exs;
                        }
                    }
                    Object invokeTarget = Modifier.isStatic(method.getModifiers()) ? null : module.getClass().getDeclaredConstructor().newInstance();
                    method.invoke(invokeTarget, args);
                } catch (Exception e) {
                    logger.error("Failed to invoke @NeedRun({}) method {} on {}",
                            type, method.getName(), module.getClass().getSimpleName(), e);
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
