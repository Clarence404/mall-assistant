package io.github.clarence404.assistant.memory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「正在回答」标记的内存实现：一个 {@link ConcurrentHashMap}（第 6 章的做法，第 10 章挪到这里）。只在一个进程里有效。
 *
 * <p>所在层：记忆。{@link ConcurrentHashMap#putIfAbsent} 和 Redis 的 {@code SET NX} 是同一个语义：不存在才放，原子。
 */
public final class MemoryConversationLocks implements ConversationLocks {

    private final Map<String, String> held = new ConcurrentHashMap<>();

    @Override
    public String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        return held.putIfAbsent(key, token) == null ? token : null;
    }

    @Override
    public void unlock(String key, String token) {
        held.remove(key, token);
    }

    @Override
    public boolean isLocked(String key) {
        return held.containsKey(key);
    }
}
