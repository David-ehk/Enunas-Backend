package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShippingProblemDto {

    @NotBlank
    @Size(max = 1000)
    @NoHtml
    private String description;
}
