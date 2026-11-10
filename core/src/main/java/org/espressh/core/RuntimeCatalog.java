package org.espressh.core;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Host-only atomic inventory; explicit Java homes share the same authority as discovery. */
public final class RuntimeCatalog {
    private volatile Map<String,RuntimeProfile> snapshot=Map.of();
    private final Set<String> directIds=new HashSet<>();
    private final Set<String> unavailableDirectIds=new HashSet<>();
    private long nextDirectId=1;
    private final Path hostHome;
    private final Consumer<RuntimeProfile> validate;
    public RuntimeCatalog(Path canonicalHostHome,Consumer<RuntimeProfile> validate){hostHome=canonicalHostHome;this.validate=validate;}
    public synchronized void refresh(Supplier<List<RuntimeProfile>> discovery) {
        Map<String,RuntimeProfile> next=new LinkedHashMap<>();Map<Path,RuntimeProfile> homes=new LinkedHashMap<>();
        for(RuntimeProfile p:List.copyOf(discovery.get())){
            validate.accept(p);
            if(p.id().equals("host")!=p.javaHome().equals(hostHome))throw new KernelException(ErrorCode.START_FAILED,"Invalid host runtime identity");
            RuntimeProfile previous=homes.putIfAbsent(p.javaHome(),p);
            if(previous!=null){
                if(!previous.version().equals(p.version()) || !previous.vendor().equals(p.vendor()) || !previous.provider().equals(p.provider()) || !previous.contextOptions().equals(p.contextOptions()))
                    throw new KernelException(ErrorCode.START_FAILED,"Ambiguous canonical home: "+p.javaHome());
                continue;
            }
            if(next.putIfAbsent(p.id(),p)!=null)throw new KernelException(ErrorCode.START_FAILED,"Duplicate runtime ID: "+p.id());
        }
        if(!next.containsKey("host"))throw new KernelException(ErrorCode.START_FAILED,"Validated host runtime is required");
        // Keep identities inherited by already-running direct-home children. Revalidation
        // belongs to each new launch, so one missing direct home cannot prevent refresh.
        Set<String> unavailable=new HashSet<>();
        for(String id:directIds) {
            RuntimeProfile previous=snapshot.get(id);
            if(previous==null)continue;
            RuntimeProfile replacement=next.get(id);
            if(replacement!=null&&!replacement.javaHome().equals(previous.javaHome()))
                throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: Direct runtime identity conflicts with discovery: "+id);
            try {
                JavaHomeMetadata metadata=JavaHomeMetadata.read(previous.javaHome());
                RuntimeProfile selected=selection(id,metadata,previous.javaHome(),next.get("host"),replacement==null?previous:replacement);
                validate.accept(selected);next.put(id,selected);
                synchronizeHomeMetadata(next,selected);
            } catch(KernelException invalid){unavailable.add(id);}
            next.putIfAbsent(id,previous);
        }
        snapshot=Map.copyOf(next);unavailableDirectIds.clear();unavailableDirectIds.addAll(unavailable);
    }
    public synchronized List<RuntimeProfile> list(){
        // A refreshed discovery name may share a home with an older inherited opaque ID.
        // Retain both lookups but publish one installation, preferring the discovery name.
        Map<Path,RuntimeProfile> homes=new LinkedHashMap<>();
        Set<Path> unavailableHomes=new HashSet<>();
        for(String id:unavailableDirectIds) {
            RuntimeProfile unavailable=snapshot.get(id);
            if(unavailable!=null)unavailableHomes.add(unavailable.javaHome());
        }
        snapshot.values().stream().filter(p->!unavailableHomes.contains(p.javaHome())).sorted(Comparator
                .comparingInt((RuntimeProfile p)->p.id().equals("host")?0:directIds.contains(p.id())?2:1)
                .thenComparing(RuntimeProfile::id))
                .forEach(p->homes.putIfAbsent(p.javaHome(),p));
        return homes.values().stream().sorted(Comparator.comparing(RuntimeProfile::id)).toList();
    }
    public synchronized RuntimeProfile forLaunch(String id){
        RuntimeProfile p=snapshot.get(id);
        if(p==null)throw new KernelException(ErrorCode.RUNTIME_NOT_FOUND,"Unknown runtime: "+id);
        if(hasDirectHome(p.javaHome())) {
            try {return materialize(id,JavaHomeMetadata.read(p.javaHome()),p.javaHome());}
            catch(KernelException invalid){unavailableDirectIds.add(id);throw invalid;}
        }
        validate.accept(p);return p;
    }

    /** The caller resolves relative paths against its LaunchRequest CWD before this call. */
    public synchronized RuntimeProfile forHome(Path path){
        JavaHomeMetadata metadata=JavaHomeMetadata.read(path);
        RuntimeProfile known=snapshot.values().stream().filter(p->p.javaHome().equals(metadata.home()))
                .sorted(Comparator.comparingInt((RuntimeProfile p)->p.id().equals("host")?0:directIds.contains(p.id())?1:2)
                        .thenComparing(RuntimeProfile::id)).findFirst().orElse(null);
        String id=known==null?nextIdentity():known.id();
        RuntimeProfile result=materialize(id,metadata,metadata.home());
        directIds.add(id);
        return result;
    }

    private RuntimeProfile materialize(String id,JavaHomeMetadata metadata,Path expectedHome){
        RuntimeProfile baseline=snapshot.get("host");
        if(baseline==null)throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: Validated host runtime is required");
        RuntimeProfile previous=snapshot.get(id);
        RuntimeProfile selected=selection(id,metadata,expectedHome,baseline,previous);
        validate.accept(selected);
        Map<String,RuntimeProfile> next=new LinkedHashMap<>(snapshot);
        next.put(id,selected);
        synchronizeHomeMetadata(next,selected);
        if(!next.equals(snapshot))snapshot=Map.copyOf(next);
        unavailableDirectIds.removeIf(unavailable->{
            RuntimeProfile entry=snapshot.get(unavailable);
            return entry==null||entry.javaHome().equals(metadata.home());
        });
        return selected;
    }

    private static void synchronizeHomeMetadata(Map<String,RuntimeProfile> profiles,RuntimeProfile selected){
        // Alias identity and discovery lifetime remain independent, but one validated
        // installation must not publish different metadata through its different IDs.
        profiles.replaceAll((id,p)->p.javaHome().equals(selected.javaHome())
                &&(!p.version().equals(selected.version())||!p.vendor().equals(selected.vendor()))
                ?new RuntimeProfile(p.id(),p.javaHome(),selected.version(),selected.vendor(),p.provider(),p.contextOptions()):p);
    }

    private boolean hasDirectHome(Path home){
        for(String id:directIds) {
            RuntimeProfile direct=snapshot.get(id);
            if(direct!=null&&direct.javaHome().equals(home))return true;
        }
        return false;
    }

    private RuntimeProfile selection(String id,JavaHomeMetadata metadata,Path expectedHome,RuntimeProfile baseline,RuntimeProfile previous){
        if(!metadata.home().equals(expectedHome))
            throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: Runtime canonical home changed: "+expectedHome);
        if(previous!=null&&(!previous.provider().equals(baseline.provider())||!previous.contextOptions().equals(baseline.contextOptions())))
            throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: JavaHome profile conflicts with the fixed Host provider policy: "+id);
        RuntimeProfile selected;
        if(id.equals("host")) {
            if(!metadata.home().equals(hostHome)||!metadata.version().equals(baseline.version())
                    ||!metadata.vendor().equals(baseline.vendor()))
                throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: Host runtime metadata does not match the running Host: "+metadata.home());
            selected=baseline;
        } else {
            selected=new RuntimeProfile(id,metadata.home(),metadata.version(),metadata.vendor(),
                    baseline.provider(),baseline.contextOptions());
            if(previous!=null&&previous.equals(selected))selected=previous;
        }
        return selected;
    }

    private String nextIdentity(){
        while(nextDirectId>0) {
            String id="direct-"+nextDirectId++;
            if(!snapshot.containsKey(id))return id;
        }
        throw new KernelException(ErrorCode.START_FAILED,"START_FAILED: Direct runtime identity space exhausted");
    }
}
