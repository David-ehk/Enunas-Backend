package com.enunas.backend.settlement.accounting;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin-entered facts for one calendar period that the system has no authoritative source for:
 * what Mollie actually deducted in fees, and the confirmed external settlement/payout reference.
 * Everything else in {@link SettlementAccountingReportService}'s report is derived live from
 * ledger_entries/payouts — this table exists ONLY for the two genuinely-external facts, so there
 * is no second, potentially-stale copy of anything the ledger already knows.
 */
@Entity
@Table(name = "settlement_accounting_inputs",
        uniqueConstraints = @UniqueConstraint(name = "uq_settlement_accounting_input_period", columnNames = "period"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementAccountingInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Calendar month, 'YYYY-MM'. */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "mollie_fees", precision = 10, scale = 2)
    private BigDecimal mollieFees;

    @Column(name = "mollie_fees_included_in_actual_payout")
    private Boolean mollieFeesIncludedInActualPayout;

    @Column(name = "payout_reference", length = 100)
    private String payoutReference;

    @Column(name = "mollie_settlement_date")
    private LocalDate mollieSettlementDate;

    @Column(length = 1000)
    private String notes;

    @Column(name = "entered_by_admin_email", nullable = false)
    private String enteredByAdminEmail;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
