package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Size;

/** Body of POST /admin/returns/{n}/release-refund. Optional; the reason is required to refund an APPROVED return. */
public record ReleaseRefundDto(@Size(max = 500) @NoHtml String overrideReason) {}
