package com.enunas.backend.admin;

/** Published after an admin's brand-approval transaction commits. */
public record BrandApprovedEvent(String email) {
}
