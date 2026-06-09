package com.enunas.backend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared money rounding + VAT gross/net conversion. Every persisted figure is rounded
 * HALF_UP to 2 decimals. VAT is always {@code gross − net}; net and gross are the only
 * canonical figures.
 */
public final class MoneyMath {

    private MoneyMath() {}

    /** Round HALF_UP to 2 decimals (null-safe). */
    public static BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** gross = round(net × (1 + vatRate), 2). */
    public static BigDecimal grossFromNet(BigDecimal net, BigDecimal vatRate) {
        return round2(net.multiply(BigDecimal.ONE.add(vatRate)));
    }

    /** net = round(gross / (1 + vatRate), 2). */
    public static BigDecimal netFromGross(BigDecimal gross, BigDecimal vatRate) {
        return gross.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
    }
}
