package com.enunas.backend.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The single place that computes money going back to a customer for order lines, and how it splits
 * into commission net / commission VAT / brand payout for the ledger. Pure — no DB, no Spring.
 * Rule per field: unit share = round(field ÷ quantity, 2); the call that brings a line to "all units
 * refunded" takes field − unit share × already refunded, so repeated partials sum exactly to the line.
 * Brand payout is derived (customer − commission net − commission VAT) so every part sums exactly.
 */
public record RefundQuote(List<Line> lines, BigDecimal shippingAmount) {

    public record LineInput(Long orderItemId, int quantity, BigDecimal customerGross,
                            BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout,
                            int alreadyRefundedUnits, int units) {
        public static LineInput of(OrderItem item, int alreadyRefundedUnits, int units) {
            if (item.getCustomerGrossAfterDiscount() == null) {
                throw new IllegalStateException("Item " + item.getId()
                        + " predates per-item pricing (legacy order) — refund it manually.");
            }
            return new LineInput(item.getId(), item.getQuantity(), item.getCustomerGrossAfterDiscount(),
                    Objects.requireNonNullElse(item.getCommissionNet(), BigDecimal.ZERO),
                    Objects.requireNonNullElse(item.getCommissionVat(), BigDecimal.ZERO),
                    Objects.requireNonNullElse(item.getBrandPayoutAmount(), BigDecimal.ZERO),
                    alreadyRefundedUnits, units);
        }
    }

    public record Line(Long orderItemId, int units, BigDecimal customerAmount,
                       BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout) {}

    public static RefundQuote of(List<LineInput> inputs, BigDecimal shippingAmount) {
        return new RefundQuote(inputs.stream().map(RefundQuote::line).toList(),
                shippingAmount.setScale(2, RoundingMode.HALF_UP));
    }

    private static Line line(LineInput in) {
        if (in.units() <= 0 || in.alreadyRefundedUnits() + in.units() > in.quantity()) {
            throw new IllegalArgumentException("Item " + in.orderItemId() + ": cannot refund " + in.units()
                    + " of " + in.quantity() + " units (" + in.alreadyRefundedUnits() + " already refunded).");
        }
        if (in.commissionNet().add(in.commissionVat()).add(in.brandPayout()).compareTo(in.customerGross()) != 0) {
            throw new IllegalStateException("Item " + in.orderItemId()
                    + " money snapshot does not sum to what the customer paid — refund it manually.");
        }
        boolean last = in.alreadyRefundedUnits() + in.units() == in.quantity();
        BigDecimal customer = part(in.customerGross(), in, last);
        BigDecimal net = part(in.commissionNet(), in, last);
        BigDecimal vat = part(in.commissionVat(), in, last);
        return new Line(in.orderItemId(), in.units(), customer, net, vat, customer.subtract(net).subtract(vat));
    }

    private static BigDecimal part(BigDecimal lineField, LineInput in, boolean last) {
        BigDecimal unit = lineField.divide(BigDecimal.valueOf(in.quantity()), 2, RoundingMode.HALF_UP);
        return last
                ? lineField.subtract(unit.multiply(BigDecimal.valueOf(in.alreadyRefundedUnits())))
                : unit.multiply(BigDecimal.valueOf(in.units()));
    }

    public BigDecimal itemsAmount()   { return sum(Line::customerAmount); }
    public BigDecimal commissionNet() { return sum(Line::commissionNet); }
    public BigDecimal commissionVat() { return sum(Line::commissionVat); }
    public BigDecimal brandPayout()   { return sum(Line::brandPayout); }
    public BigDecimal total()         { return itemsAmount().add(shippingAmount); }

    private BigDecimal sum(Function<Line, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
