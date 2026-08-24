package com.example.subscription.service;

import com.example.subscription.model.AkwaPayPayment;
import com.example.subscription.model.AkwaPayPaymentStatus;
import com.example.subscription.model.ManualPayment;
import com.example.subscription.model.ManualPaymentStatus;
import com.example.subscription.model.PaymentTransaction;
import com.example.subscription.model.ScanPurchase;
import com.example.subscription.model.ScanPurchaseStatus;
import com.example.subscription.repository.InMemoryAkwaPayPaymentRepository;
import com.example.subscription.repository.InMemoryManualPaymentRepository;
import com.example.subscription.repository.InMemoryPaymentRepository;
import com.example.subscription.repository.InMemoryScanPurchaseRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregates revenue across FOUR payment rails: Paystack (PaymentTransaction),
 * manual mobile-money/bank-transfer submissions an admin approved
 * (ManualPayment), AkwaPay-funded subscriptions (AkwaPayPayment), and
 * AkwaPay-funded scan purchases (ScanPurchase). A deposit only counts once
 * it's confirmed - "verified" for Paystack, "approved" for the other three -
 * never while still pending/awaiting.
 *
 * ScanPurchase uses ScanPlan rather than Plan, so its amount is read via
 * ScanPlan.getAmountCedis() directly rather than through a shared Plan type -
 * both return a plain int, so the totals combine the same way regardless of
 * which plan type backed the sale.
 */
@Service
public class StatsService {

    private final InMemoryPaymentRepository paymentRepository;
    private final InMemoryManualPaymentRepository manualPaymentRepository;
    private final InMemoryAkwaPayPaymentRepository akwaPayPaymentRepository;
    private final InMemoryScanPurchaseRepository scanPurchaseRepository;

    public StatsService(InMemoryPaymentRepository paymentRepository,
                         InMemoryManualPaymentRepository manualPaymentRepository,
                         InMemoryAkwaPayPaymentRepository akwaPayPaymentRepository,
                         InMemoryScanPurchaseRepository scanPurchaseRepository) {
        this.paymentRepository = paymentRepository;
        this.manualPaymentRepository = manualPaymentRepository;
        this.akwaPayPaymentRepository = akwaPayPaymentRepository;
        this.scanPurchaseRepository = scanPurchaseRepository;
    }

    private List<PaymentTransaction> successfulTransactions() {
        return paymentRepository.findAll().stream()
                .filter(tx -> tx.getStatus() == PaymentTransaction.Status.SUCCESS && tx.getVerifiedAt() != null)
                .collect(Collectors.toList());
    }

    private List<ManualPayment> approvedManualPayments() {
        return manualPaymentRepository.findByStatus(ManualPaymentStatus.APPROVED).stream()
                .filter(p -> p.getReviewedAt() != null)
                .collect(Collectors.toList());
    }

    private List<AkwaPayPayment> approvedAkwaPaySubscriptions() {
        return akwaPayPaymentRepository.findByStatus(AkwaPayPaymentStatus.APPROVED).stream()
                .filter(p -> p.getReviewedAt() != null)
                .collect(Collectors.toList());
    }

    private List<ScanPurchase> approvedAkwaPayScanPurchases() {
        return scanPurchaseRepository.findAll().stream()
                .filter(p -> p.getStatus() == ScanPurchaseStatus.APPROVED && p.getReviewedAt() != null)
                .collect(Collectors.toList());
    }

    /** Total cedis deposited across all four rails on a given calendar date. */
    public int depositsOn(LocalDate date) {
        int paystack = successfulTransactions().stream()
                .filter(tx -> tx.getVerifiedAt().toLocalDate().equals(date))
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapaySub = approvedAkwaPaySubscriptions().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapayScan = approvedAkwaPayScanPurchases().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .mapToInt(p -> p.getScanPlan().getAmountCedis())
                .sum();
        return paystack + manual + akwapaySub + akwapayScan;
    }

    /** Total cedis deposited since a given timestamp (e.g. now.minusDays(7) for "this week"). */
    public int depositsSince(LocalDateTime since) {
        int paystack = successfulTransactions().stream()
                .filter(tx -> !tx.getVerifiedAt().isBefore(since))
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .filter(p -> !p.getReviewedAt().isBefore(since))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapaySub = approvedAkwaPaySubscriptions().stream()
                .filter(p -> !p.getReviewedAt().isBefore(since))
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapayScan = approvedAkwaPayScanPurchases().stream()
                .filter(p -> !p.getReviewedAt().isBefore(since))
                .mapToInt(p -> p.getScanPlan().getAmountCedis())
                .sum();
        return paystack + manual + akwapaySub + akwapayScan;
    }

    public int totalDepositsAllTime() {
        int paystack = successfulTransactions().stream()
                .mapToInt(tx -> tx.getPlan().getAmountCedis())
                .sum();
        int manual = approvedManualPayments().stream()
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapaySub = approvedAkwaPaySubscriptions().stream()
                .mapToInt(p -> p.getPlan().getAmountCedis())
                .sum();
        int akwapayScan = approvedAkwaPayScanPurchases().stream()
                .mapToInt(p -> p.getScanPlan().getAmountCedis())
                .sum();
        return paystack + manual + akwapaySub + akwapayScan;
    }

    public long successfulPaymentCount() {
        return successfulTransactions().size()
                + approvedManualPayments().size()
                + approvedAkwaPaySubscriptions().size()
                + approvedAkwaPayScanPurchases().size();
    }

    public long successfulPaymentCountOn(LocalDate date) {
        long paystack = successfulTransactions().stream()
                .filter(tx -> tx.getVerifiedAt().toLocalDate().equals(date))
                .count();
        long manual = approvedManualPayments().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .count();
        long akwapaySub = approvedAkwaPaySubscriptions().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .count();
        long akwapayScan = approvedAkwaPayScanPurchases().stream()
                .filter(p -> p.getReviewedAt().toLocalDate().equals(date))
                .count();
        return paystack + manual + akwapaySub + akwapayScan;
    }
}