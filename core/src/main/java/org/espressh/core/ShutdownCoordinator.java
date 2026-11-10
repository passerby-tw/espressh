package org.espressh.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** Host-side shutdown sequencing. Provider cancellation and confirmed results remain distinct. */
public final class ShutdownCoordinator {
    public record Result(List<Long> unconfirmed) { public Result { unconfirmed=List.copyOf(unconfirmed); } public boolean complete(){return unconfirmed.isEmpty();} }
    private final ProcessTable table;
    public ShutdownCoordinator(ProcessTable table){this.table=table;}
    public Result stopChildren(Duration timeout,LongConsumer requestProviderStop) throws InterruptedException {
        if(timeout.isNegative())throw new IllegalArgumentException("Negative shutdown timeout");
        table.freezeSpawn();
        List<ProcessRecord> children=table.records().stream().filter(p->p.pid()!=1).toList();
        for(ProcessRecord p:children)if(p.requestStop())requestProviderStop.accept(p.pid());
        long budget=timeout.toNanos(),start=System.nanoTime();List<Long> unconfirmed=new ArrayList<>();
        for(ProcessRecord p:children){
            long remaining=Math.max(0,budget-(System.nanoTime()-start));
            if(p.await(Duration.ofNanos(remaining)).isEmpty())unconfirmed.add(p.pid());
        }
        return new Result(unconfirmed);
    }
}
