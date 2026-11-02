package mine.coding.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多方块写入的原子性：按顺序写，任一次失败就把已写入的键按相反顺序恢复为写入前的值，再抛出原始异常。
 * 纯算法、不依赖 Minecraft API；调用方只提供"怎么写"（写失败由写入器自己抛异常，文案由调用方决定）。
 */
public final class AtomicWrites {
    @FunctionalInterface
    public interface Writer<K,V>{ void write(K key,V value); }

    private AtomicWrites(){}

    /**
     * @param target   需要写入的键值（调用方保证只包含真正要改的键）
     * @param previous 每个键写入前的原值，回滚用
     * @return 成功写入的键数
     */
    public static <K,V> int writeAll(Map<K,V> target,Map<K,V> previous,Writer<K,V> writer){
        List<K> written=new ArrayList<>(target.size());
        try{
            for(Map.Entry<K,V> entry:target.entrySet()){writer.write(entry.getKey(),entry.getValue());written.add(entry.getKey());}
            return written.size();
        }catch(RuntimeException failure){
            for(int i=written.size()-1;i>=0;i--){
                K key=written.get(i);
                if(!previous.containsKey(key))continue;
                try{writer.write(key,previous.get(key));}catch(RuntimeException rollbackFailure){failure.addSuppressed(rollbackFailure);}
            }
            throw failure;
        }
    }
}
