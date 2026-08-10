package com.enunas.backend.discount.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.customer.Customer;
import com.enunas.backend.customer.CustomerRepository;
import com.enunas.backend.discount.DiscountCodeRepository;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.OrderRepository;
import com.enunas.backend.order.OrderService;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base for discount payment-flow integration tests. Boots the full app on a random port against a
 * throwaway PostgreSQL 16 (Testcontainers) with Flyway-managed schema, and the mock-payments
 * profile so Mollie is simulated. Seeds actors/products via repositories and truncates after each
 * test for isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "mock-payments"})
public abstract class AbstractDiscountIntegrationTest {

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected BCryptPasswordEncoder passwordEncoder;

    @Autowired protected UserRepository userRepository;
    @Autowired protected CustomerRepository customerRepository;
    @Autowired protected BrandPartnerRepository brandPartnerRepository;
    @Autowired protected BrandEconomicsRepository brandEconomicsRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected ProductColorRepository productColorRepository;
    @Autowired protected ProductVariantRepository productVariantRepository;
    @Autowired protected ProductListingRepository productListingRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected DiscountCodeRepository discountCodeRepository;
    @Autowired protected LedgerRepository ledgerRepository;
    @Autowired protected com.enunas.backend.payout.PayoutRepository payoutRepository;
    @Autowired protected com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository brandPayoutProfileRepository;

    @Autowired protected OrderService orderService;
    @Autowired protected LedgerService ledgerService;

    @AfterEach
    void cleanDatabase() {
        // CASCADE truncates child/element-collection tables (analytics, catalogue categories, etc.).
        jdbc.execute("TRUNCATE TABLE settlement_accounting_inputs, payouts, brand_payout_profiles, " +
                "settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, user_addresses, oauth_accounts, customers, users RESTART IDENTITY CASCADE");
    }

    // ===== Seeding =====

    protected User seedUser(String email, String rawPassword, Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .adminApproved(true)
                .build());
    }

    protected User seedAdmin() {
        return seedUser("admin@it.local", "Admin123!", Role.ADMIN);
    }

    protected User seedCustomer() {
        User u = seedUser("customer@it.local", "Customer123!", Role.CUSTOMER);
        customerRepository.save(Customer.builder().user(u).build());
        return u;
    }

    protected record BrandFixture(BrandPartner brand, User user) {}

    protected BrandFixture seedBrand(String brandName, String slug, String commissionRate) {
        User u = seedUser(slug + "@it.local", "Brand123!", Role.BRAND_PARTNER);
        BrandPartner b = BrandPartner.builder().user(u).brandName(brandName).slug(slug).build();
        b.setStatus(BrandStatus.ACTIVE);
        b = brandPartnerRepository.save(b);
        brandEconomicsRepository.save(BrandEconomics.builder()
                .brandPartner(b)
                .defaultCommissionRate(new BigDecimal(commissionRate))
                .build());
        return new BrandFixture(b, u);
    }

    /**
     * Persists Product -> ProductColor -> ProductVariant -> ProductListing; returns the listing id.
     * {@code priceEuros} is the GROSS price (priceInputMode = GROSS); the order flow derives net.
     */
    protected long seedListing(BrandPartner brand, User creator, String priceEuros, int stock) {
        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 6);
        Product p = productRepository.save(Product.builder()
                .name(name)
                // products.slug is NOT NULL + UNIQUE since V11; the random name keeps it unique.
                .slug(name.toLowerCase())
                .brand(brand)
                .creator(creator)
                .build());
        ProductColor color = productColorRepository.save(ProductColor.builder()
                .sku(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
                .color("Black")
                .colorFamily(ColorFamily.BLACK)
                .product(p)
                .build());
        ProductVariant variant = productVariantRepository.save(ProductVariant.builder()
                .productColor(color)
                .size("M")
                .stockQuantity(stock)
                .product(p)
                .build());
        ProductListing listing = productListingRepository.save(ProductListing.builder()
                .product(p)
                .variant(variant)
                .price(new BigDecimal(priceEuros))
                .priceInputMode(PriceInputMode.GROSS)
                .currency("EUR")
                .active(true)
                .build());
        return listing.getId();
    }

    // ===== HTTP helpers =====

    @SuppressWarnings("unchecked")
    protected String login(String email, String rawPassword) {
        Map<String, Object> body = Map.of("email", email, "password", rawPassword);
        Map<String, Object> resp = rest.postForObject("/auth/login", body, Map.class);
        return (String) resp.get("token");
    }

    protected HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected Map<String, Object> item(long listingId, int qty) {
        return Map.of("listingId", listingId, "quantity", qty);
    }

    @SuppressWarnings("rawtypes")
    protected org.springframework.http.ResponseEntity<Map> postOrder(
            String token, String discountCode, List<Map<String, Object>> items) {
        Map<String, Object> address = Map.of(
                "firstName", "John", "lastName", "Doe",
                "street", "Hauptstrasse", "houseNumber", "1",
                "city", "Berlin", "postalCode", "10115", "country", "DE");
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("shippingAddress", address);
        if (discountCode != null) body.put("discountCode", discountCode);
        return rest.exchange("/orders", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    protected org.springframework.http.ResponseEntity<Map> createAdminDiscount(String adminToken, Map<String, Object> body) {
        return rest.exchange("/admin/discounts", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    protected org.springframework.http.ResponseEntity<Map> createBrandDiscount(String brandToken, Map<String, Object> body) {
        return rest.exchange("/brand/discounts", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, auth(brandToken)), Map.class);
    }

    /** Drives a PENDING order to PAID via the exact webhook code path (synchronous, deterministic). */
    protected void confirmPaid(long orderId) {
        orderService.confirmPaymentByWebhook(orderId);
    }

    /** Backdates every PENDING_RELEASE entry for an order into the past, then runs the release job
     *  synchronously — used instead of waiting out the real hold-days window in tests. */
    protected void releaseAllPending(long orderId) {
        jdbc.update("UPDATE ledger_entries SET payout_eligible_at = ? WHERE order_id = ?",
                java.time.LocalDateTime.now().minusDays(1), orderId);
        ledgerService.releasePendingBalances();
    }

    /** Drives a brand's AVAILABLE balance through generate → approve → markAsPaid, exactly the real
     *  admin flow (POST /admin/payouts/generate, /approve, /paid). Requires a BrandPayoutProfile to
     *  already exist for the brand. Returns the created payout's id. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected long generateApproveAndPayPayout(String adminToken, long brandId, String externalReference) {
        rest.exchange("/admin/payouts/generate", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(auth(adminToken)), java.util.List.class);

        java.util.Map<String, Object> row = jdbc.queryForList(
                "SELECT id FROM payouts WHERE brand_partner_id = ? ORDER BY id DESC LIMIT 1", brandId)
                .get(0);
        long payoutId = ((Number) row.get("id")).longValue();

        rest.exchange("/admin/payouts/" + payoutId + "/approve", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(auth(adminToken)), Map.class);
        rest.exchange("/admin/payouts/" + payoutId + "/paid", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("externalReference", externalReference), auth(adminToken)), Map.class);
        return payoutId;
    }

    // ===== DB assertion accessors =====

    protected long orderId(org.springframework.http.ResponseEntity<Map> resp) {
        return ((Number) resp.getBody().get("id")).longValue();
    }

    protected Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }

    protected List<Map<String, Object>> orderItemRows(long orderId) {
        return jdbc.queryForList("SELECT * FROM order_items WHERE order_id = ? ORDER BY id", orderId);
    }

    protected int usedCount(String code) {
        return jdbc.queryForObject("SELECT used_count FROM discount_codes WHERE code = ?", Integer.class, code);
    }

    protected BigDecimal brandPending(Long brandId) {
        return brandEconomicsRepository.findByBrandPartner_Id(brandId).orElseThrow().getPendingBalance();
    }
}
