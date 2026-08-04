package fun.bm.lophine.enums;

public enum GlobalEntitiesCounterType {
    DISABLED(false, false, false),
    DEFAULT_SYNC(true, false, true),
    DEFAULT_ASYNC(true, true, true),
    PRECISE(true, false, false);

    private final boolean enabled;
    private final boolean async;
    private final boolean defaultModule;

    private GlobalEntitiesCounterType(boolean enabled, boolean async, boolean defaultModule) {
        this.enabled = enabled;
        this.async = async;
        this.defaultModule = defaultModule;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isDefaultModule() {
        return defaultModule;
    }
}
