package com.enunas.backend.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Reason supplied by an admin when rejecting a brand application or product. */
@Data
public class RejectionDto {

    @Size(max = 1000)
    private String reason;
}
