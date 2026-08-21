package com.enunas.backend.user;

/** Published after a new customer account is durably created (password signup or Google signup). */
public record WelcomeEmailEvent(String email) {
}
