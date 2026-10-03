package carpet.script.utils;

//import carpet.fakes.MinecraftServerInterface;
//import carpet.fakes.ServerWorldInterface;

public class Experimental {
    /*
    public static Value reloadOne(MinecraftServer server)
    {
        LevelStorageSource.LevelStorageAccess session = ((MinecraftServerInterface) server).getCMSession();
        DataPackConfig dataPackSettings = session.getDataPacks();
        PackRepository resourcePackManager = server.getPackRepository();
        DataPackConfig dataPackSettings2 = MinecraftServer.configurePackRepository(resourcePackManager, dataPackSettings == null ? DataPackConfig.DEFAULT : dataPackSettings, false);

        CarpetScriptServer.LOG.error("datapacks: {}", dataPackSettings2.getEnabled());

        MinecraftServer.ReloadableResources serverRM = ((MinecraftServerInterface) server).getResourceManager();
        var resourceManager = serverRM.resourceManager();


        final RegistryAccess.Frozen currentRegistry = server.registryAccess();

        ImmutableList<PackResources> packsToLoad = resourcePackManager.getAvailableIds().stream().map(server.getPackRepository()::getPack).filter(Objects::nonNull).map(Pack::open).collect(ImmutableList.toImmutableList());
        final CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packsToLoad);
        //ReloadableServerResources managers = ReloadableServerResources.loadResources(resources, currentRegistry, server.isDedicatedServer() ? Commands.CommandSelection.DEDICATED : Commands.CommandSelection.INTEGRATED, server.getFunctionCompilationLevel(), Util.backgroundExecutor(), Util.backgroundExecutor()).join();

        //believe the other one will fillup based on the datapacks only.
        //resources.close();


        //not sure its needed, but doesn't seem to have a negative effect and might be used in some custom shtuff
        //serverRM.updateGlobals();
        DynamicOps<Tag> dynamicOps = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());//, (ResourceManager) resourceManager);

        WorldData saveProperties = session.getDataTag(dynamicOps, dataPackSettings2, server.registryAccess().allElementsLifecycle());

        //RegistryReadOps<Tag> registryOps = RegistryReadOps.create(NbtOps.INSTANCE, serverRM.getResourceManager(), (RegistryAccess.RegistryHolder) server.registryAccess());
        //WorldData saveProperties = session.getDataTag(registryOps, dataPackSettings2);
        if (saveProperties == null) return Value.NULL;
        //session.backupLevelDataFile(server.getRegistryManager(), saveProperties); // no need

        // MinecraftServer.createWorlds
        // save properties should now contain dimension settings
        WorldGenSettings generatorOptions = saveProperties.worldGenSettings();
        boolean bl = generatorOptions.isDebug();
        long l = generatorOptions.seed();
        long m = BiomeManager.obfuscateSeed(l);
        Map<ResourceKey<Level>, ServerLevel> existing_worlds = ((MinecraftServerInterface) server).getCMWorlds();
        List<Value> addeds = new ArrayList<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : generatorOptions.dimensions().entrySet())
        {
            ResourceKey<LevelStem> registryKey = entry.getKey();
            CarpetScriptServer.LOG.error("Analysing workld: {}", registryKey.location());
            if (!existing_worlds.containsKey(registryKey))
            {
                addeds.add(ValueConversions.of(registryKey.location()));
                ResourceKey<Level> registryKey2 = ResourceKey.create(Registry.DIMENSION_REGISTRY, registryKey.location());
                Holder<DimensionType> holder2 = (entry.getValue()).typeHolder();
                ChunkGenerator chunkGenerator3 = entry.getValue().generator();
                DerivedLevelData unmodifiableLevelProperties = new DerivedLevelData(saveProperties, ((ServerWorldInterface) server.overworld()).getWorldPropertiesCM());
                ServerLevel serverWorld2 = new ServerLevel(server, Util.backgroundExecutor(), session, unmodifiableLevelProperties, registryKey2, entry.getValue(), WorldTools.NOOP_LISTENER, bl, m, ImmutableList.of(), false);
                server.overworld().getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(serverWorld2.getWorldBorder()));
                existing_worlds.put(registryKey2, serverWorld2);
            }
        }
        return ListValue.wrap(addeds);
    }

    public static Value reloadTwo(MinecraftServer server)
    {
        LevelStorageSource.LevelStorageAccess session = ((MinecraftServerInterface)server).getCMSession();
        DataPackConfig dataPackSettings = session.getDataPacks();
        PackRepository resourcePackManager = server.getPackRepository();

        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(new WorldLoader.PackConfig(resourcePackManager, dataPackSettings == null ? DataPackConfig.DEFAULT : dataPackSettings, false), Commands.CommandSelection.DEDICATED, 4);


        final WorldStem stem = WorldLoader.load(initConfig, (resourceManager, dataPackConfigx) -> {
            RegistryAccess.Writable writable = RegistryAccess.builtinCopy();
            DynamicOps<Tag> dynamicOps = RegistryOps.createAndLoad(NbtOps.INSTANCE, writable, (ResourceManager) resourceManager);
            WorldData worldData = session.getDataTag(dynamicOps, dataPackConfigx, writable.allElementsLifecycle());
            return Pair.of(worldData, writable.freeze());
        }, WorldStem::new, Util.backgroundExecutor(), Runnable::run).join();
        WorldGenSettings generatorOptions = stem.worldData().worldGenSettings();

        boolean bl = generatorOptions.isDebug();
        long l = generatorOptions.seed();
        long m = BiomeManager.obfuscateSeed(l);
        Map<ResourceKey<Level>, ServerLevel> existing_worlds = ((MinecraftServerInterface)server).getCMWorlds();
        List<Value> addeds = new ArrayList<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : generatorOptions.dimensions().entrySet()) {
            ResourceKey<LevelStem> registryKey = entry.getKey();
            if (!existing_worlds.containsKey(registryKey))
            {
                ResourceKey<Level> resourceKey2 = ResourceKey.create(Registry.DIMENSION_REGISTRY, registryKey.location());
                DerivedLevelData derivedLevelData = new DerivedLevelData(stem.worldData(), ((ServerWorldInterface) server.overworld()).getWorldPropertiesCM());
                ServerLevel serverLevel2 = new ServerLevel(server, Util.backgroundExecutor(), session, derivedLevelData, resourceKey2, entry.getValue(), WorldTools.NOOP_LISTENER, bl, m, ImmutableList.of(), false);
                server.overworld().getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(serverLevel2.getWorldBorder()));
                existing_worlds.put(resourceKey2, serverLevel2);
                addeds.add(ValueConversions.of(registryKey.location()));
            }
        }
        ((MinecraftServerInterface)server).reloadAfterReload(stem.registryAccess());
        return ListValue.wrap(addeds);
    }

     */
}
