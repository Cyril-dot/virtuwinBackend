package com.example.subscription.repository;

import com.example.subscription.model.AkwaPayPendingScanIntent;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConcurrentHashMap-backed store for pending AkwaPay scan-purchase intents,
 * keyed by reference. Same style as InMemoryScanPurchaseRepository.
 *
 * See AkwaPayPendingScanIntent's class doc for why this being in-memory
 * (rather than a DB table) is a real, accepted trade-off and not an
 * oversight.
 */
@Repository
public class InMemoryAkwaPayPendingScanIntentRepository {

    private final ConcurrentHashMap<String, AkwaPayPendingScanIntent> store = new ConcurrentHashMap<>();

    public AkwaPayPendingScanIntent save(AkwaPayPendingScanIntent intent) {
        store.put(intent.getReference(), intent);
        return intent;
    }

    public Optional<AkwaPayPendingScanIntent> findByReference(String reference) {
        return Optional.ofNullable(store.get(reference));
    }

    public boolean existsByReference(String reference) {
        return store.containsKey(reference);
    }

    public void deleteByReference(String reference) {
        store.remove(reference);
    }

    /** Everything created before the given instant, oldest first — mirrors the JPA sweep query. */
    public List<AkwaPayPendingScanIntent> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff) {
        return store.values().stream()
                .filter(i -> i.getCreatedAt().isBefore(cutoff))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    public List<AkwaPayPendingScanIntent> findAll() {
        return List.copyOf(store.values());
    }
}