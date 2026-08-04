package com.enunas.backend.compliance;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository;
import com.enunas.backend.exception.BrandNotFoundException;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.OrderItemRepository;
import com.enunas.backend.order.ShippingAddress;
import com.enunas.backend.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Read-only §22f-UStG export: per brand + month, one row per supplied line item with all 9
 * Pflichtangaben, joined across brand_partners + brand_payout_profiles + orders/order_items/payments.
 * Distributed storage is compliant — what matters is retrievability per sale. No money recompute.
 */
@Service
@RequiredArgsConstructor
public class Vat22fExportService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final BrandPartnerRepository brandPartnerRepository;
    private final BrandPayoutProfileRepository payoutProfileRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public List<Vat22fExportRowDto> export(Long brandId, String period) {
        BrandPartner brand = brandPartnerRepository.findById(brandId)
                .orElseThrow(() -> new BrandNotFoundException("Brand not found: " + brandId));
        YearMonth ym = parsePeriod(period);
        LocalDateTime startUtc = ym.atDay(1).atStartOfDay(BERLIN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = ym.plusMonths(1).atDay(1).atStartOfDay(BERLIN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        String iban = payoutProfileRepository.findByBrandPartner_Id(brandId)
                .map(BrandPayoutProfile::getIban).orElse(null);
        String supplierEmail = brand.getContactEmail() != null ? brand.getContactEmail()
                : (brand.getUser() != null ? brand.getUser().getEmail() : null);
        String shipmentOrigin = formatAddress(
                brand.getAddressStreet(), brand.getAddressPostalCode(),
                brand.getAddressCity(), brand.getAddressCountry());

        return orderItemRepository.findVat22fLineItems(brandId, startUtc, endUtc).stream()
                .map(oi -> toRow(oi, brand, iban, supplierEmail, shipmentOrigin))
                .toList();
    }

    private Vat22fExportRowDto toRow(OrderItem oi, BrandPartner brand, String iban,
                                     String supplierEmail, String shipmentOrigin) {
        Order order = oi.getOrder();
        ShippingAddress dest = order.getShippingAddress();
        BigDecimal amount = oi.getCustomerGrossAfterDiscount() != null
                ? oi.getCustomerGrossAfterDiscount() : oi.getLineTotal();
        String txId = paymentRepository.findByOrderId(order.getId())
                .map(p -> p.getTransactionId()).orElse(null);

        return Vat22fExportRowDto.builder()
                // (1) supplier — fall back to brandName if the legal name was never filled
                .supplierLegalName(brand.getLegalName() != null ? brand.getLegalName() : brand.getBrandName())
                .supplierStreet(brand.getAddressStreet())
                .supplierPostalCode(brand.getAddressPostalCode())
                .supplierCity(brand.getAddressCity())
                .supplierCountry(brand.getAddressCountry())
                // (2)
                .supplierVatId(brand.getVatId())
                .supplierTaxNumber(brand.getTaxNumber())
                // (4)
                .shipmentOrigin(shipmentOrigin)
                // (7)
                .supplierEmail(supplierEmail)
                .supplierWebsite(brand.getWebsiteUrl())
                // (8)
                .supplierIban(iban)
                // (5)
                .destinationName(dest != null ? formatDestinationName(dest.getFirstName(), dest.getLastName()) : null)
                .destinationStreet(dest != null ? formatDestinationStreet(dest.getStreet(), dest.getHouseNumber()) : null)
                .destinationPostalCode(dest != null ? dest.getPostalCode() : null)
                .destinationCity(dest != null ? dest.getCity() : null)
                .destinationCountry(dest != null ? dest.getCountry() : null)
                // (6)
                .saleTimestamp(order.getCreatedAt() != null ? order.getCreatedAt().toString() : null)
                .saleAmountGross(amount != null ? amount.toPlainString() : null)
                .currency(order.getCurrency())
                // (9)
                .itemDescription(describe(oi))
                .orderNumber(order.getOrderNumber())
                .paymentTransactionId(txId)
                .build();
    }

    private static String describe(OrderItem oi) {
        return oi.getProductSnapshotName()
                + " (" + oi.getVariantSnapshotColor()
                + ", " + oi.getVariantSnapshotSize()
                + ", SKU " + oi.getVariantSnapshotSku() + ")";
    }

    private static String formatAddress(String street, String postal, String city, String country) {
        if (street == null && postal == null && city == null && country == null) return null;
        return (n(street) + ", " + n(postal) + " " + n(city) + ", " + n(country)).trim();
    }

    private static String n(String s) {
        return s != null ? s : "";
    }

    private static String formatDestinationName(String firstName, String lastName) {
        if (firstName == null && lastName == null) return null;
        return (n(firstName) + " " + n(lastName)).trim();
    }

    private static String formatDestinationStreet(String street, String houseNumber) {
        if (street == null && houseNumber == null) return null;
        return (n(street) + " " + n(houseNumber)).trim();
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period format, expected YYYY-MM: " + period);
        }
    }
}
