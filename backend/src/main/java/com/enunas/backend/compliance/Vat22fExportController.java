package com.enunas.backend.compliance;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only §22f-UStG export. Serves the recording duty under Abs. 4 (machine-readable export on
 * request) and lets the operator pull a brand's master data + sales to verify VAT manually.
 *
 * GET /admin/brands/{brandId}/22f-export?period=YYYY-MM&format=csv|json
 */
@RestController
@RequestMapping("/admin/brands")
@RequiredArgsConstructor
public class Vat22fExportController {

    private final Vat22fExportService exportService;

    @GetMapping("/{brandId}/22f-export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> export(
            @PathVariable Long brandId,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "json") String format) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'period' (YYYY-MM) is required");
        }
        List<Vat22fExportRowDto> rows = exportService.export(brandId, period);

        if ("csv".equalsIgnoreCase(format)) {
            String csv = toCsv(rows);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"22f-export-" + brandId + "-" + period + ".csv\"")
                    .body(csv);
        }
        return ResponseEntity.ok(rows);
    }

    private static String toCsv(List<Vat22fExportRowDto> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(Vat22fExportRowDto.csvHeader().stream().map(Vat22fExportController::esc)
                .reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        for (Vat22fExportRowDto r : rows) {
            sb.append(r.toCsvRow().stream().map(Vat22fExportController::esc)
                    .reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        }
        return sb.toString();
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
