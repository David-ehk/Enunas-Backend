package com.enunas.backend.compliance;

import lombok.Builder;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * One §22f-UStG record per supplied line item. Flattens the 9 Pflichtangaben across supplier (brand)
 * and order/payment data. All amounts EUR. Used for both JSON and CSV export (column order fixed by
 * {@link #csvHeader()} / {@link #toCsvRow()}).
 */
@Getter
@Builder
public class Vat22fExportRowDto {

    // (1) Name + Anschrift des Lieferers
    private final String supplierLegalName;
    private final String supplierStreet;
    private final String supplierPostalCode;
    private final String supplierCity;
    private final String supplierCountry;
    // (2) Steuernummer + USt-IdNr
    private final String supplierVatId;
    private final String supplierTaxNumber;
    // (4) Ort des Versandbeginns (= business address, per assumption)
    private final String shipmentOrigin;
    // (7) Elektronische Adresse oder Website
    private final String supplierEmail;
    private final String supplierWebsite;
    // (8) Bankverbindung
    private final String supplierIban;
    // (5) Bestimmungsort (Lieferadresse)
    private final String destinationName;
    private final String destinationStreet;
    private final String destinationPostalCode;
    private final String destinationCity;
    private final String destinationCountry;
    // (6) Zeitpunkt + Höhe des Umsatzes
    private final String saleTimestamp;
    private final String saleAmountGross;
    private final String currency;
    // (9) Beschreibung des Gegenstands + Bestell-/Transaktionsnummer
    private final String itemDescription;
    private final String orderNumber;
    private final String paymentTransactionId;

    public static List<String> csvHeader() {
        return List.of(
                "supplierLegalName", "supplierStreet", "supplierPostalCode", "supplierCity", "supplierCountry",
                "supplierVatId", "supplierTaxNumber", "shipmentOrigin", "supplierEmail", "supplierWebsite",
                "supplierIban", "destinationName", "destinationStreet", "destinationPostalCode", "destinationCity",
                "destinationCountry", "saleTimestamp", "saleAmountGross", "currency", "itemDescription",
                "orderNumber", "paymentTransactionId");
    }

    public List<String> toCsvRow() {
        return Arrays.asList(
                supplierLegalName, supplierStreet, supplierPostalCode, supplierCity, supplierCountry,
                supplierVatId, supplierTaxNumber, shipmentOrigin, supplierEmail, supplierWebsite,
                supplierIban, destinationName, destinationStreet, destinationPostalCode, destinationCity,
                destinationCountry, saleTimestamp, saleAmountGross, currency, itemDescription,
                orderNumber, paymentTransactionId);
    }
}
