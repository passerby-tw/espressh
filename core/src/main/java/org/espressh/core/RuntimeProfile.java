package org.espressh.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Validated configuration snapshot, not a live Context or mutable registry projection. */
public record RuntimeProfile(String id,Path javaHome,String version,String vendor,String provider,
                             Map<String,String> contextOptions) {
    public RuntimeProfile {
        Objects.requireNonNull(id);Objects.requireNonNull(javaHome);Objects.requireNonNull(version);
        Objects.requireNonNull(vendor);Objects.requireNonNull(provider);
        if(id.isBlank()||!javaHome.isAbsolute())throw new IllegalArgumentException("Invalid profile identity");
        contextOptions=Map.copyOf(contextOptions);
    }
}
