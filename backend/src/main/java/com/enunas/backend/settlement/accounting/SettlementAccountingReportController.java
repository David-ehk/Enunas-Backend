package com.enunas.backend.settlement.accounting;

import com.enunas.backend.settlement.accounting.dto.SettlementAccountingReportDto;
import com.enunas.backend.settlement.accounting.dto.UpsertAccountingInputDto;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only platform-level accounting export. Aggregates already-authoritative ledger/payout data
 * — introduces no new business logic for payments, commission, discounts, or refunds (spec item 16).
 *
 *  - GET /admin/settlements/{settlementId}/accounting-report                  → full JSON (spec §2/§13)
 *  - GET /admin/settlements/{settlementId}/accounting-report/export?format=   → flat settlement-level row (spec §4)
 *  - PUT /admin/settlements/{period}/accounting-input                        → admin-entered Mollie fees / payout ref
 */
@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementAccountingReportController {

    private final SettlementAccountingReportService reportService;

    @GetMapping("/{settlementId}/accounting-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SettlementAccountingReportDto> getReport(@PathVariable String settlementId) {
        return ResponseEntity.ok(reportService.generateReport(settlementId));
    }

    @GetMapping("/{settlementId}/accounting-report/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> exportReport(
            @PathVariable String settlementId,
            @RequestParam(defaultValue = "json") String format) {
        SettlementAccountingReportDto report = reportService.generateReport(settlementId);
        if ("csv".equalsIgnoreCase(format)) {
            String csv = toCsv(report);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"accounting-report-" + settlementId + ".csv\"")
                    .body(csv);
        }
        return ResponseEntity.ok(report);
    }

    @PutMapping("/{period}/accounting-input")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> upsertInput(
            @PathVariable String period,
            @RequestBody UpsertAccountingInputDto dto,
            @AuthenticationPrincipal User admin) {
        reportService.upsertInput(period, dto, admin.getEmail());
        return ResponseEntity.noContent().build();
    }

    /** Flat, single-row CSV per spec §4's exact column order. RFC-4180 escaping. */
    private static String toCsv(SettlementAccountingReportDto r) {
        String[] header = {
                "settlement_id", "period_start", "period_end", "currency", "settlement_date",
                "payout_reference", "mollie_gross_inflows", "enunas_commission_net", "enunas_vat_rate",
                "enunas_vat_amount", "enunas_commission_gross", "brand_product_amount",
                "brand_shipping_amount", "brand_payout_amount", "mollie_fees", "refunds",
                "actual_payout_amount", "payout_account", "booking_date"
        };
        String[] row = {
                r.getSettlementId(), s(r.getPeriodStart()), s(r.getPeriodEnd()), r.getCurrency(), s(r.getSettlementDate()),
                s(r.getPayoutReference()), s(r.getTotalCustomerPayments()), s(r.getEnunasCommissionNet()), s(r.getEnunasVatRate()),
                s(r.getEnunasVatAmount()), s(r.getEnunasCommissionGross()), s(r.getBrandProductAmount()),
                s(r.getBrandShippingAmount()), s(r.getBrandPayoutAmount()), s(r.getMollieFees()), s(r.getRefundAmount()),
                s(r.getActualPayoutAmount()), "MOLLIE", s(r.getSettlementDate())
        };
        StringBuilder sb = new StringBuilder();
        sb.append(join(header)).append("\r\n").append(join(row)).append("\r\n");
        return sb.toString();
    }

    private static String join(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(esc(fields[i]));
        }
        return sb.toString();
    }

    private static String s(Object v) {
        return v == null ? "" : v.toString();
    }

    /** RFC-4180 escaping: quote fields containing a comma, quote, CR or LF; double inner quotes. */
    private static String esc(String v) {
        String s = v == null ? "" : v;
        if (s.contains("\"") || s.contains(",") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
