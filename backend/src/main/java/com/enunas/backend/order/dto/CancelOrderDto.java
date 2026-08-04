package com.enunas.backend.order.dto;

import com.enunas.backend.order.CancelReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CancelOrderDto {

    @NotNull
    private CancelReason reason;

    @Size(max = 500)
    @NoHtml
    private String note;
}
