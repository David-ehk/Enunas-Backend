package com.enunas.backend.settlement;

import com.enunas.backend.settlement.dto.SettleRequestDto;
import com.enunas.backend.settlement.dto.SettlementRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only monthly settlement report + settlement marker.
 *  - GET  /admin/settlements?period=YYYY-MM  → unsettled brands with activity (default: last closed month).
 *  - POST /admin/settlements/{brandId}/{period} → freeze + mark settled (closed-period only, once).
 */
@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SettlementRowDto>> list(
            @RequestParam(required = false) String period) {
        return ResponseEntity.ok(settlementService.getUnsettled(period));
    }

    @PostMapping("/{brandId}/{period}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SettlementRowDto> settle(
            @PathVariable Long brandId,
            @PathVariable String period,
            @RequestBody(required = false) SettleRequestDto body) {
        String invoiceReference = body != null ? body.getInvoiceReference() : null;
        return ResponseEntity.ok(settlementService.settle(brandId, period, invoiceReference));
    }
}
