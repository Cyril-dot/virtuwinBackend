package com.example.subscription.repository;

import com.example.subscription.model.ScanSession;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryScanSessionRepository {

    private final ConcurrentHashMap<String, ScanSession> store = new ConcurrentHashMap<>();

    public ScanSession save(ScanSession s) { store.put(s.getId(), s); return s; }

    public Optional<ScanSession> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<ScanSession> findByAkwapayReference(String ref) {
        return store.values().stream().filter(s -> ref.equals(s.getAkwapayReference())).findFirst();
    }

    public List<ScanSession> findByEmail(String email) {
        return store.values().stream().filter(s -> email.equals(s.getEmail())).collect(Collectors.toList());
    }

    public List<ScanSession> findActive() {
        return store.values().stream()
                .filter(s -> s.getStatus() == ScanSession.Status.ACTIVE)
                .collect(Collectors.toList());
    }
}