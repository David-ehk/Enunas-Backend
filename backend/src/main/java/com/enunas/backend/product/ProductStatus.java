package com.enunas.backend.product;

public enum ProductStatus {
    ACTIVE,
    SUSPENDED,  // admin-hidden, not visible to customers
    REJECTED,   // admin-rejected, brand partner can see the reason
    INACTIVE,
    ARCHIVED
}
