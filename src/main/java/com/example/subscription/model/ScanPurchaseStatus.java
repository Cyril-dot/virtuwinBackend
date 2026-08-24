package com.example.subscription.model;

/**
 * AWAITING_PAYMENT is new: it's where an AkwaPay-funded purchase sits between
 * intent creation and AkwaPay confirming the charge succeeded (webhook or
 * sweep). It is functionally "not yet usable, not yet reviewable" — distinct
 * from PENDING, which means "screenshot submitted, waiting on a human".
 *
 * A purchase in AWAITING_PAYMENT that AkwaPay confirms moves straight to
 * APPROVED — there is no manual review step on that path. See
 * ScanPurchaseAkwaPayService.
 */
public enum ScanPurchaseStatus {
    AWAITING_PAYMENT,
    PENDING,
    APPROVED,
    REJECTED,
    USED
}