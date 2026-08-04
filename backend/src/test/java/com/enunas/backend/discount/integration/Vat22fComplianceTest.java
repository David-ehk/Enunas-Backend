package com.enunas.backend.discount.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §22f recording: onboarding captures supplier legal name + address (mandatory) and vatId
 * (optional by default; no validation, no country guard), and the admin export returns all 9
 * Pflichtangaben per sale as JSON and CSV. EmailService is mocked so /brandpartner/apply doesn't
 * touch SMTP.
 */
class Vat22fComplianceTest extends AbstractDiscountIntegrationTest {

    @MockitoBean private EmailService emailService; // no-op the verification email on apply
    @Autowired private BrandPayoutProfileRepository payoutProfileRepository;

    // ===== Onboarding — Feld 1 (mandatory) =====
    @Test
    void onboarding_requiresLegalNameAndAddress_else400() {
        Map<String, Object> body = baseApply();
        body.remove("legalName");
        body.remove("addressStreet");
        assertThat(apply(body).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void onboarding_persistsAddressAndVatId_andReturnsThem() {
        Map<String, Object> body = baseApply();
        body.put("vatId", "DE123456789");
        ResponseEntity<Map> resp = apply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().get("legalName")).isEqualTo("Acme Fashion GmbH");
        assertThat(resp.getBody().get("addressCity")).isEqualTo("Berlin");
        assertThat(resp.getBody().get("addressCountry")).isEqualTo("DE");
        assertThat(resp.getBody().get("vatId")).isEqualTo("DE123456789");

        Map<String, Object> dbRow = jdbc.queryForMap(
                "SELECT legal_name, address_postal_code, vat_id FROM brand_partners WHERE brand_name = ?",
                body.get("brandName"));
        assertThat(dbRow.get("legal_name")).isEqualTo("Acme Fashion GmbH");
        assertThat(dbRow.get("address_postal_code")).isEqualTo("10115");
        assertThat(dbRow.get("vat_id")).isEqualTo("DE123456789");
    }

    // ===== Onboarding — Feld 2 vatId optional by default; no country guard =====
    @Test
    void onboarding_vatIdOptionalByDefault_andForeignCountryStillOnboardable() {
        Map<String, Object> body = baseApply();
        body.put("country", "FR"); // foreign — must NOT be blocked
        body.remove("vatId");      // default toggle false → optional
        assertThat(apply(body).getStatusCode().value()).isEqualTo(201);
    }

    // ===== Export — all 9 Pflichtangaben, JSON + CSV =====
    @Test
    @SuppressWarnings("unchecked")
    void export_returnsNineFields_inJsonAndCsv() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();

        // Enrich the brand with §22f master data + a payout profile (IBAN).
        BrandPartner brand = brandPartnerRepository.findById(bid).orElseThrow();
        brand.setLegalName("BrandA GmbH");
        brand.setAddressStreet("Hauptstr. 5");
        brand.setAddressPostalCode("10115");
        brand.setAddressCity("Berlin");
        brand.setAddressCountry("DE");
        brand.setVatId("DE999999999");
        brand.setTaxNumber("12/345/67890");
        brandPartnerRepository.save(brand);
        payoutProfileRepository.save(BrandPayoutProfile.builder()
                .brandPartner(brand).iban("DE89370400440532013000").bankAccountHolder("BrandA GmbH").build());

        long listing = seedListing(a.brand(), a.user(), "119.00", 10);
        String admin = login("admin@it.local", "Admin123!");
        String cust = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        confirmPaid(oid);
        String orderNumber = (String) orderRow(oid).get("order_number");
        String period = YearMonth.now(ZoneId.of("Europe/Berlin")).toString();

        // --- JSON ---
        ResponseEntity<List> json = rest.exchange(
                "/admin/brands/" + bid + "/22f-export?period=" + period + "&format=json",
                HttpMethod.GET, new HttpEntity<>(auth(admin)), List.class);
        assertThat(json.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = json.getBody();
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(r.get("supplierLegalName")).isEqualTo("BrandA GmbH");            // (1)
        assertThat(r.get("supplierVatId")).isEqualTo("DE999999999");                // (2)
        assertThat(r.get("shipmentOrigin")).asString().contains("Berlin");          // (4)
        assertThat(r.get("supplierEmail")).isNotNull();                             // (7)
        assertThat(r.get("supplierIban")).isEqualTo("DE89370400440532013000");      // (8)
        assertThat(r.get("destinationCity")).isEqualTo("Berlin");                   // (5)
        assertThat(r.get("saleAmountGross")).isNotNull();                           // (6)
        assertThat((String) r.get("itemDescription")).contains("Black");            // (9)
        assertThat(r.get("orderNumber")).isEqualTo(orderNumber);                    // (9)
        assertThat((String) r.get("paymentTransactionId")).startsWith("pay_mock_"); // (9)

        // --- CSV ---
        ResponseEntity<String> csv = rest.exchange(
                "/admin/brands/" + bid + "/22f-export?period=" + period + "&format=csv",
                HttpMethod.GET, new HttpEntity<>(auth(admin)), String.class);
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getHeaders().getContentType().toString()).contains("text/csv");
        String[] lines = csv.getBody().split("\r\n");
        assertThat(lines[0]).contains("supplierLegalName").contains("orderNumber").contains("paymentTransactionId");
        assertThat(lines).hasSize(2); // header + one sale
        assertThat(lines[1]).contains(orderNumber).contains("BrandA GmbH");
    }

    // ===== Admin §22f master-data update (PATCH /admin/brands/{brandId}) =====
    @Test
    void adminUpdatesMasterData_persistsAndReturns() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        Map<String, Object> body = masterData();
        ResponseEntity<Map> resp = patchBrand(admin, bid, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("legalName")).isEqualTo("BrandA GmbH");
        assertThat(resp.getBody().get("addressCity")).isEqualTo("Berlin");
        assertThat(resp.getBody().get("vatId")).isEqualTo("DE111111111");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT legal_name, address_country, tax_number FROM brand_partners WHERE id = ?", bid);
        assertThat(row.get("legal_name")).isEqualTo("BrandA GmbH");
        assertThat(row.get("address_country")).isEqualTo("DE");
        assertThat(row.get("tax_number")).isEqualTo("12/345/67890");
    }

    @Test
    void adminMasterDataUpdate_nonAdmin_forbidden() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        String brandToken = login("brand-a@it.local", "Brand123!"); // ROLE_BRAND_PARTNER, not ADMIN
        assertThat(patchBrand(brandToken, a.brand().getId(), masterData()).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void adminMasterDataUpdate_invalidCountryOrEmptyRequired_badRequest() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        Map<String, Object> badCountry = masterData();
        badCountry.put("addressCountry", "D"); // 1 char → @Size(min=2,max=2)
        assertThat(patchBrand(admin, bid, badCountry).getStatusCode().value()).isEqualTo(400);

        Map<String, Object> emptyRequired = masterData();
        emptyRequired.put("legalName", ""); // @NotBlank
        assertThat(patchBrand(admin, bid, emptyRequired).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void adminMasterDataUpdate_unknownBrand_notFound() {
        seedAdmin();
        String admin = login("admin@it.local", "Admin123!");
        assertThat(patchBrand(admin, 999_999L, masterData()).getStatusCode().value()).isEqualTo(404);
    }

    // ===== Onboarding decoupled from email (A1) =====
    @Test
    void onboarding_succeedsEvenWhenVerificationEmailThrows() {
        // The best-effort AFTER_COMMIT email blows up — the application must still persist + succeed.
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendVerificationEmail(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        Map<String, Object> body = baseApply();
        ResponseEntity<Map> resp = apply(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(201); // no rollback
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM brand_partners WHERE brand_name = ?", Integer.class, body.get("brandName"));
        assertThat(count).isEqualTo(1); // brand durably persisted despite the email failure
    }

    // ===== domestic derived from addressCountry (reverse-charge input fix) =====
    @Test
    void onboarding_DE_derivesDomesticTrue() {
        Map<String, Object> body = baseApply(); // addressCountry "DE"
        ResponseEntity<Map> resp = apply(body);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().get("domestic")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT domestic FROM brand_partners WHERE brand_name = ?",
                Boolean.class, body.get("brandName"))).isTrue();
    }

    @Test
    void onboarding_FR_derivesDomesticFalse() {
        Map<String, Object> body = baseApply();
        body.put("addressCountry", "FR");
        ResponseEntity<Map> resp = apply(body);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().get("domestic")).isEqualTo(false);
    }

    @Test
    void onboarding_lowercaseDe_isNormalizedAndDerivesTrue() {
        Map<String, Object> body = baseApply();
        body.put("addressCountry", "de"); // lower-case
        ResponseEntity<Map> resp = apply(body);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().get("domestic")).isEqualTo(true);
        // stored normalized to upper-case
        assertThat(jdbc.queryForObject("SELECT address_country FROM brand_partners WHERE brand_name = ?",
                String.class, body.get("brandName"))).isEqualTo("DE");
    }

    @Test
    void adminUpdate_flippingCountry_flipsDomestic() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        Map<String, Object> toFr = masterData();
        toFr.put("addressCountry", "FR");
        assertThat(patchBrand(admin, bid, toFr).getBody().get("domestic")).isEqualTo(false);

        Map<String, Object> toDe = masterData(); // "DE"
        assertThat(patchBrand(admin, bid, toDe).getBody().get("domestic")).isEqualTo(true);
    }

    @Test
    void e2e_commissionVat_domesticDE19Percent_foreignFRreverseCharge() {
        seedAdmin();
        seedCustomer();
        String admin = login("admin@it.local", "Admin123!");
        String cust = login("customer@it.local", "Customer123!");

        // DE brand → domestic derived → 19% commission VAT.
        BrandFixture de = seedBrand("BrandDE", "brand-de", "0.18");
        patchBrand(admin, de.brand().getId(), masterData()); // country "DE"
        long deListing = seedListing(de.brand(), de.user(), "119.00", 10);
        long deOid = orderId(postOrder(cust, null, List.of(item(deListing, 1))));
        Map<String, Object> deItem = jdbc.queryForMap(
                "SELECT commission_vat, reverse_charge, brand_is_domestic FROM order_items WHERE order_id = ?", deOid);
        assertThat((BigDecimal) deItem.get("commission_vat")).isEqualByComparingTo("3.42");
        assertThat(deItem.get("reverse_charge")).isEqualTo(false);
        assertThat(deItem.get("brand_is_domestic")).isEqualTo(true);

        // FR brand → foreign → reverse charge → 0% commission VAT.
        BrandFixture fr = seedBrand("BrandFR", "brand-fr", "0.18");
        Map<String, Object> frData = masterData();
        frData.put("addressCountry", "FR");
        patchBrand(admin, fr.brand().getId(), frData);
        long frListing = seedListing(fr.brand(), fr.user(), "119.00", 10);
        long frOid = orderId(postOrder(cust, null, List.of(item(frListing, 1))));
        Map<String, Object> frItem = jdbc.queryForMap(
                "SELECT commission_vat, reverse_charge, brand_is_domestic, brand_payout FROM order_items WHERE order_id = ?", frOid);
        assertThat((BigDecimal) frItem.get("commission_vat")).isEqualByComparingTo("0.00");
        assertThat(frItem.get("reverse_charge")).isEqualTo(true);
        assertThat(frItem.get("brand_is_domestic")).isEqualTo(false);
        assertThat((BigDecimal) frItem.get("brand_payout")).isEqualByComparingTo("101.00"); // 119 − 18 (no VAT)
    }

    // ===== helpers =====

    private Map<String, Object> masterData() {
        Map<String, Object> body = new HashMap<>();
        body.put("legalName", "BrandA GmbH");
        body.put("addressStreet", "Hauptstr. 5");
        body.put("addressPostalCode", "10115");
        body.put("addressCity", "Berlin");
        body.put("addressCountry", "DE");
        body.put("vatId", "DE111111111");
        body.put("taxNumber", "12/345/67890");
        return body;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> patchBrand(String token, long brandId, Map<String, Object> body) {
        return rest.exchange("/admin/brands/" + brandId, HttpMethod.PATCH,
                new HttpEntity<>(body, auth(token)), Map.class);
    }

    private Map<String, Object> baseApply() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new HashMap<>();
        body.put("email", "brand-" + unique + "@apply.local");
        body.put("password", "Brand123!");
        body.put("brandName", "Acme " + unique);
        body.put("firstName", "Erika");
        body.put("lastName", "Mustermann");
        body.put("legalName", "Acme Fashion GmbH");
        body.put("addressStreet", "Friedrichstr. 1");
        body.put("addressPostalCode", "10115");
        body.put("addressCity", "Berlin");
        body.put("addressCountry", "DE");
        return body;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> apply(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/brandpartner/apply", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
