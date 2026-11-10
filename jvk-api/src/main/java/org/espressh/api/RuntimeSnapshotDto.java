package org.espressh.api;

import java.util.List;

/** Host provenance and Guest runtime values copied from the authoritative JVK catalog. */
public record RuntimeSnapshotDto(String hostVersion, String hostJavaHome, String espressoVersion,
                                 List<RuntimeInfoDto> runtimes) {
    public RuntimeSnapshotDto {
        runtimes = List.copyOf(runtimes);
    }
}
