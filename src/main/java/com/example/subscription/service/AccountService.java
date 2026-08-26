package com.example.subscription.service;

import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Admin;
import com.example.subscription.model.Plan;
import com.example.subscription.model.UserAccount;
import com.example.subscription.repository.InMemoryAdminRepository;
import com.example.subscription.repository.InMemoryUserRepository;
import com.example.subscription.util.CodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AccountService {

    private final InMemoryUserRepository userRepository;
    private final InMemoryAdminRepository adminRepository;

    @Value("${account.password.length:10}")
    private int passwordLength;

    // Simple, permissive patterns - tighten as needed for your locale/format.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\+?[0-9]{7,15}$");

    public AccountService(InMemoryUserRepository userRepository, InMemoryAdminRepository adminRepository) {
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
    }

    /**
     * Step 1 of the flow: user registers with an identifier - either an
     * email or a phone number - optionally with an admin's referral code
     * (e.g. from a referral link like
     * https://yourapp.com/register?ref=REF-8K3PQZ).
     *
     * TODO: the "password" parameter is currently accepted but NOT used or
     * stored - it's a placeholder for a future flow and is silently
     * ignored below. The real password is still auto-generated later in
     * assignSubscription(), same as before this change. Remove this
     * parameter or wire it up before relying on it; as written it is a
     * footgun for anyone assuming it sets the account's password.
     *
     * The account sits "unpaid" until they pay. Idempotent: registering
     * again with an identifier that has no active subscription just
     * returns the existing account as-is (its original referral
     * attribution, if any, is kept).
     */
    public synchronized UserAccount register(String identifier, String password, String referralCode) {
        validateIdentifier(identifier);
        // NOTE: password is intentionally unused for now - see TODO above.

        UserAccount account = userRepository.findByUsername(identifier).orElse(null);

        if (account == null) {
            account = new UserAccount(identifier);
            if (referralCode != null && !referralCode.isBlank()) {
                Admin admin = adminRepository.findByReferralCode(referralCode)
                        .orElseThrow(() -> new ApiException("Invalid referral code", HttpStatus.BAD_REQUEST));
                if (!admin.isActive()) {
                    throw new ApiException("This referral link is no longer active", HttpStatus.BAD_REQUEST);
                }
                account.setReferredByAdminCode(referralCode);
            }
            userRepository.save(account);
            return account;
        }

        if (account.hasActiveSubscription()) {
            throw new ApiException(
                    "This email/phone already has an active subscription. Finish using it before registering/subscribing again.",
                    HttpStatus.CONFLICT);
        }

        // Already registered, no active subscription - nothing to do, just return it.
        return account;
    }

    /**
     * Called only from the payment-verify step, after Paystack confirms
     * success. Requires the identifier to already be registered, and
     * requires that it does NOT currently have an active/unused
     * subscription (one subscription at a time - no topping up, no
     * stacking). This is where the password is generated and assigned -
     * unchanged from before.
     */
    public synchronized UserAccount assignSubscription(String identifier, Plan plan) {
        UserAccount account = userRepository.findByUsername(identifier)
                .orElseThrow(() -> new ApiException(
                        "No registered account found for " + identifier + ". Please register first.",
                        HttpStatus.NOT_FOUND));

        if (account.hasActiveSubscription()) {
            throw new ApiException(
                    "This account already has an active subscription. You can only have one at a time.",
                    HttpStatus.CONFLICT);
        }

        String password = CodeGenerator.generatePassword(passwordLength);

        account.setPassword(password);
        account.setPlan(plan);
        account.setUsageExpiresAt(null);          // countdown starts on next login
        account.setSubscriptionConsumed(false);
        account.setActiveSessionToken(null);

        userRepository.save(account);
        return account;
    }

    /** Can this identifier currently pay for a plan? Must be registered and have no active subscription. */
    public boolean canPurchase(String identifier) {
        return userRepository.findByUsername(identifier)
                .map(a -> !a.hasActiveSubscription())
                .orElse(false); // not registered at all -> cannot purchase
    }

    /** Returns the account for this identifier, or null if not registered. Used for password lookups after approval. */
    public UserAccount getAccountOrNull(String identifier) {
        return userRepository.findByUsername(identifier).orElse(null);
    }

    /**
     * Validates that the given identifier looks like a valid email address
     * or a valid phone number. Throws ApiException(400) otherwise.
     */
    private void validateIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ApiException("Email or phone number is required", HttpStatus.BAD_REQUEST);
        }
        boolean looksLikeEmail = EMAIL_PATTERN.matcher(identifier).matches();
        boolean looksLikePhone = PHONE_PATTERN.matcher(identifier).matches();
        if (!looksLikeEmail && !looksLikePhone) {
            throw new ApiException(
                    "Please provide a valid email address or phone number",
                    HttpStatus.BAD_REQUEST);
        }
    }
}