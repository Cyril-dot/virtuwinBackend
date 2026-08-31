package com.example.subscription.repository;

import com.example.subscription.model.AkwaPayPendingScanSessionIntent;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryAkwaPayPendingScanSessionIntentRepository {

    private final ConcurrentHashMap<String, AkwaPayPendingScanSessionIntent> store = new ConcurrentHashMap<>();

    public void save(AkwaPayPendingScanSessionIntent i) { store.put(i.getReference(), i); }

    public Optional<AkwaPayPendingScanSessionIntent> findByReference(String ref) {
        return Optional.ofNullable(store.get(ref));
    }

    public void deleteByReference(String ref) { store.remove(ref); }

    public List<AkwaPayPendingScanSessionIntent> findByCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff) {
        return store.values().stream()
                .filter(i -> i.getCreatedAt().isBefore(cutoff))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .collect(Collectors.toList());
    }
}