package com.example.subscription.repository;

import com.example.subscription.model.AkwaPayPayment;
import com.example.subscription.model.AkwaPayPaymentStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Same style as InMemoryManualPaymentRepository. */
@Repository
public class InMemoryAkwaPayPaymentRepository {

    private final ConcurrentHashMap<String, AkwaPayPayment> store = new ConcurrentHashMap<>();

    public AkwaPayPayment save(AkwaPayPayment payment) {
        store.put(payment.getId(), payment);
        return payment;
    }

    public Optional<AkwaPayPayment> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<AkwaPayPayment> findAll() {
        return List.copyOf(store.values());
    }

    public List<AkwaPayPayment> findByStatus(AkwaPayPaymentStatus status) {
        return store.values().stream().filter(p -> p.getStatus() == status).toList();
    }

    public List<AkwaPayPayment> findByEmail(String email) {
        return store.values().stream().filter(p -> p.getEmail().equalsIgnoreCase(email)).toList();
    }
}