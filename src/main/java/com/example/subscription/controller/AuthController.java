package com.example.subscription.controller;

import com.example.subscription.dto.ApiResponse;
import com.example.subscription.dto.LoginRequest;
import com.example.subscription.dto.RegisterRequest;
import com.example.subscription.exception.ApiException;
import com.example.subscription.model.Session;
import com.example.subscription.model.UserAccount;
import com.example.subscription.service.AccountService;
import com.example.subscription.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionService sessionService;
    private final AccountService accountService;

    public AuthController(SessionService sessionService, AccountService accountService) {
        this.sessionService = sessionService;
        this.accountService = accountService;
    }

    /**
     * Step 1: register with an email or phone number. No password is set
     * yet - the account sits "unpaid" until the user pays for a plan (see
     * /api/payment/*), at which point a login password is auto-generated.
     *
     * TODO: RegisterRequest is assumed to still only expose getEmail() and
     * getReferralCode(). AccountService.register(...) now also accepts a
     * password parameter, but it is currently unused/ignored by the
     * service, so we pass null here rather than depend on a
     * req.getPassword() that may not exist on the DTO yet. If/when that
     * parameter gets wired up in AccountService, add a password field to
     * RegisterRequest and pass it through here.
     */
    @PostMapping("/register")
    public ApiResponse<Object> register(@Valid @RequestBody RegisterRequest req) {
        UserAccount account = accountService.register(req.getEmail(), null, req.getReferralCode());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", account.getUsername());
        result.put("status", account.isPaid() ? "PAID" : "UNPAID");
        result.put("referred", account.getReferredByAdminCode() != null);

        return ApiResponse.ok(
                "Registered. Now pay for a plan to receive your login password.",
                result);
    }

    @PostMapping("/login")
    public ApiResponse<Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        Session session = sessionService.login(req.getUsername(), req.getPassword(), ip);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", session.getToken());
        result.put("expiresAt", session.getExpiresAt());
        result.put("secondsRemaining", session.secondsRemaining());

        return ApiResponse.ok("Login successful", result);
    }

    @PostMapping("/logout")
    public ApiResponse<Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        sessionService.logout(token);
        return ApiResponse.ok("Logged out successfully", null);
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ApiException("Missing Authorization Bearer token", HttpStatus.UNAUTHORIZED);
        }
        return authHeader.substring(7);
    }
}