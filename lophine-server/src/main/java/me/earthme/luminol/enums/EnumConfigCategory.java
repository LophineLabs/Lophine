package me.earthme.luminol.enums;

public enum EnumConfigCategory {
    OPTIMIZATIONS("optimizations"),   // optimize performance feature
    FIXES("fixes"),                   // fix not vanilla or other bugs caused by folia/paper
    MISC("misc"),                     // unknown classify features
    FUNCTION("function"),             // new functions
    EXPERIMENT("experiment"),         // experimental features
    UNSUPPORTED("unsupported"),       // features we do not want anyone to use
    REMOVED("removed"),               // removed config
    ROOT(null);

    private final String baseKeyName;

    EnumConfigCategory(String baseKeyName) {
        this.baseKeyName = baseKeyName;
    }

    public String getBaseKeyName() {
        return this.baseKeyName;
    }
}