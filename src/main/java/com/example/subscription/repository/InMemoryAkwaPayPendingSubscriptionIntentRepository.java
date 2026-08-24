package com.example.subscription.repository;

import com.example.subscription.model.AkwaPayPendingSubscriptionIntent;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Same shape as InMemoryAkwaPayPendingScanIntentRepository, scoped to subscription payments. */
@Repository
public class InMemoryAkwaPayPendingSubscriptionIntentRepository {

    private final ConcurrentHashMap<String, AkwaPayPendingSubscriptionIntent> store = new ConcurrentHashMap<>();

    public AkwaPayPendingSubscriptionIntent save(AkwaPayPendingSubscriptionIntent intent) {
        store.put(intent.getReference(), intent);
        return intent;
    }

    public Optional<AkwaPayPendingSubscriptionIntent> findByReference(String reference) {
        return Optional.ofNullable(store.get(reference));
    }

    public boolean existsByReference(String reference) {
        return store.containsKey(reference);
    }

    public void deleteByReference(String reference) {
        store.remove(reference);
    }

    public List<AkwaPayPendingSubscriptionIntent> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff) {
        return store.values().stream()
                .filter(i -> i.getCreatedAt().isBefore(cutoff))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    public List<AkwaPayPendingSubscriptionIntent> findAll() {
        return List.copyOf(store.values());
    }
}