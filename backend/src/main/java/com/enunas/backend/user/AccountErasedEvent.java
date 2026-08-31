package com.enunas.backend.user;

/**
 * Published once an account erasure (DSGVO Art. 17) has committed. Carries the address the account
 * had BEFORE it was replaced with a tombstone — by the time the listener runs there is no longer
 * anything on the user to send to, which is the point.
 */
public record AccountErasedEvent(String formerEmail) {}
