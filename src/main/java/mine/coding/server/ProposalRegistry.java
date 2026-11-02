package mine.coding.server;
import java.util.*;
import java.util.function.LongSupplier;

/** Server-generated identifiers, ownership, expiry and consume-once state. */
public final class ProposalRegistry<T> {
    private record Proposal<T>(String id,String dimension,T value,long expires,Set<String> consumed){}
    private final Map<UUID,Proposal<T>> entries=new HashMap<>();
    private final LongSupplier time;
    public ProposalRegistry(){this(System::currentTimeMillis);}
    public ProposalRegistry(LongSupplier time){this.time=time;}
    public String put(UUID owner,String dimension,T value){String id=UUID.randomUUID().toString();entries.put(owner,new Proposal<>(id,dimension,value,time.getAsLong()+600000,new HashSet<>()));return id;}
    public T get(UUID owner,String id,String dimension){Proposal<T> p=lookup(owner,id,dimension);return p.value();}
    public T consume(UUID owner,String id,String key,String dimension){Proposal<T> p=lookup(owner,id,dimension);if(key==null||key.length()>128||!p.consumed().add(key))throw new IllegalArgumentException("proposal already confirmed or rejected");return p.value();}
    public boolean isConsumed(UUID owner,String id,String key,String dimension){return lookup(owner,id,dimension).consumed().contains(key);}
    private Proposal<T> lookup(UUID owner,String id,String dimension){Proposal<T> p=entries.get(owner);if(p==null||!p.id().equals(id))throw new IllegalArgumentException("unknown or superseded plan");if(p.expires()<=time.getAsLong()){entries.remove(owner);throw new IllegalArgumentException("plan expired");}if(!p.dimension().equals(dimension))throw new IllegalArgumentException("plan dimension changed");return p;}
    public void remove(UUID owner){entries.remove(owner);}
    public void clear(){entries.clear();}
    public int size(){return entries.size();}
    public void expire(){entries.values().removeIf(e->e.expires()<=time.getAsLong());}
    /** 一轮过期清理：计划与项目草稿（最长 16384 字符的蓝图）必须一起清，不能只清一半。 */
    public static void expireAll(ProposalRegistry<?> plans,ProposalRegistry<?> projectDrafts){plans.expire();projectDrafts.expire();}
}
