package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandReturnAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * BrandPartner → ReturnAddressSnapshotFactory → ReturnAddressSnapshot.
 *
 * <p>This is the only class in the return-request flow that resolves a brand into a return
 * address. {@link OrderService} does not know the fallback rule; it hands this factory a
 * {@link BrandPartner} and gets back a frozen {@link ReturnAddressSnapshot}, which is all
 * {@link ReturnOrder#applyShipToSnapshot} accepts. Resolution itself still lives in exactly one
 * place — {@link BrandReturnAddress#of} — this factory only adds the freeze-time timestamp and the
 * "incomplete master data" warning.
 *
 * <p>{@code BrandPartnerResponseDto} separately calls {@code BrandReturnAddress.of(...)} to preview
 * a brand's own effective address on its profile — that is a live read for display, not a
 * commitment, so it does not go through this factory and does not need to.
 */
@Slf4j
@Component
public class ReturnAddressSnapshotFactory {

    public ReturnAddressSnapshot create(BrandPartner brand, String returnNumber) {
        BrandReturnAddress address = BrandReturnAddress.of(brand);
        if (!address.isRoutable()) {
            log.warn("Return {} for brand {} has no routable return address — customer will be told "
                            + "to contact the seller. Brand master data is incomplete.",
                    returnNumber, brand != null ? brand.getId() : null);
        }
        return new ReturnAddressSnapshot(address, LocalDateTime.now());
    }
}
