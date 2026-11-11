package io.github.clarence404.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认操作的内存实现：两个 {@link ConcurrentHashMap}，一个存确认（含结果），一个存「谁抢到了」（第 7 章的做法，第 10 章挪到这里）。
 *
 * <p>所在层：Agent 的存储。{@code assistant.state.store=memory}（默认）时用；只在一个进程里有效，重启就没了。
 * {@link #claim} 用 {@link ConcurrentHashMap#putIfAbsent}：和 Redis 的 {@code SET NX} 是同一个语义（不存在才放，原子），所以两种实现的行为一样，
 * 同一套契约测试都能过。
 */
public final class MemoryConfirmationStore implements ConfirmationStore {

    private final Map<String, StoredConfirmation> entries = new ConcurrentHashMap<>();
    private final Map<String, ConfirmationOutcome.Decision> claims = new ConcurrentHashMap<>();

    @Override
    public void save(StoredConfirmation confirmation, Duration ttl) {
        entries.put(confirmation.id(), confirmation);
    }

    @Override
    public Optional<StoredConfirmation> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public Optional<ConfirmationOutcome.Decision> claim(
            String id, ConfirmationOutcome.Decision decision, Duration ttl) {
        return Optional.ofNullable(claims.putIfAbsent(id, decision));
    }

    @Override
    public void complete(String id, ConfirmationOutcome outcome, Duration ttl) {
        // 存的是同一个结果对象：重复点确认拿到的就是它（第 7 章的测试按「同一个对象」写）
        entries.computeIfPresent(id, (k, c) -> c.withOutcome(outcome));
    }

    @Override
    public List<StoredConfirmation> findByOwner(String ownerKey) {
        return entries.values().stream()
                .filter(c -> c.ownerKey().equals(ownerKey))
                .toList();
    }

    @Override
    public void sweep(Instant cutoff) {
        entries.values().removeIf(c -> c.expiresAt().isBefore(cutoff));
        claims.keySet().retainAll(entries.keySet());
    }
}
