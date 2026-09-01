package com.enunas.backend.media;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "mock-payments"})
@Testcontainers
class MediaEndToEndIntegrationTest {

    private static final String PRODUCT_BUCKET = "enunas-media-e2e-products";
    private static final String BRAND_BUCKET = "enunas-media-e2e-brands";

    @Container
    static final S3MockContainer S3_MOCK =
            new S3MockContainer("latest").withInitialBuckets(PRODUCT_BUCKET + "," + BRAND_BUCKET);

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("enunas.media.buckets.product", () -> PRODUCT_BUCKET);
        registry.add("enunas.media.buckets.brand", () -> BRAND_BUCKET);
        registry.add("enunas.media.endpoint", S3_MOCK::getHttpEndpoint);
        registry.add("enunas.media.cdn-base-url", () -> "https://cdn.it.local");
    }

    /**
     * S3Config wires real {@code DefaultCredentialsProvider.create()} beans (correct for
     * production — not overridden here). Credential resolution is deferred until the first
     * real presign/S3 call, so setting these system properties up-front satisfies
     * {@code DefaultCredentialsProviderChain} (which checks {@code aws.accessKeyId} /
     * {@code aws.secretAccessKey} before env vars / {@code ~/.aws} / IMDS) without touching
     * S3Config or wiring a hardcoded credentials object into any bean. Dummy values only —
     * S3Mock does not validate SigV4 signatures.
     */
    @BeforeAll
    static void setDummyCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @AfterAll
    static void clearDummyCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandPartnerRepository brandPartnerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private String login(String email, String password) {
        Map<String, Object> resp = rest.postForObject("/auth/login",
                Map.of("email", email, "password", password), Map.class);
        return (String) resp.get("token");
    }

    private void putBytes(String uploadUrl, String contentType, byte[] body) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", contentType)
                        .header("x-amz-tagging", "media-status=pending")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    @Test
    void productImage_presignUploadConfirmAndGet_resolvesToCdnUrl() throws Exception {
        User brandUser = userRepository.save(User.builder()
                .email("e2e-brand@it.local").password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(true).build());
        BrandPartner brand = BrandPartner.builder()
                .user(brandUser).brandName("E2E Brand").slug("e2e-brand").build();
        brand.setStatus(BrandStatus.ACTIVE);
        brandPartnerRepository.save(brand);
        Product product = productRepository.save(Product.builder()
                .name("E2E Tee").slug("e2e-tee").brand(brand).creator(brandUser).build());

        String token = login("e2e-brand@it.local", "Brand123!");
        byte[] imageBytes = "hello-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> presignResp = rest.exchange(
                "/products/" + product.getId() + "/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("purpose", "PRODUCT_IMAGE", "contentType", "image/jpeg",
                        "contentLength", imageBytes.length), auth(token)), Map.class);
        assertThat(presignResp.getStatusCode().is2xxSuccessful()).as("presign: %s", presignResp.getBody()).isTrue();
        String key = (String) presignResp.getBody().get("key");
        String uploadUrl = (String) presignResp.getBody().get("uploadUrl");

        putBytes(uploadUrl, "image/jpeg", imageBytes);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> createResp = rest.exchange(
                "/products/" + product.getId() + "/media/images", HttpMethod.POST,
                new HttpEntity<>(Map.of("storageKey", key, "primary", true, "displayOrder", 0),
                        auth(token)), Map.class);
        assertThat(createResp.getStatusCode().is2xxSuccessful()).as("create: %s", createResp.getBody()).isTrue();
        assertThat(createResp.getBody().get("imageUrl")).isEqualTo("https://cdn.it.local/" + key);

        ResponseEntity<List> listResp = rest.exchange(
                "/products/" + product.getId() + "/media/images", HttpMethod.GET, null, List.class);
        assertThat(listResp.getBody()).hasSize(1);
    }

    @Test
    void brandLogo_presignUploadConfirmAndGetMe_resolvesToCdnUrl() throws Exception {
        User brandUser = userRepository.save(User.builder()
                .email("e2e-logo@it.local").password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(true).build());
        BrandPartner brand = BrandPartner.builder()
                .user(brandUser).brandName("E2E Logo Brand").slug("e2e-logo-brand").build();
        brand.setStatus(BrandStatus.ACTIVE);
        brandPartnerRepository.save(brand);

        String token = login("e2e-logo@it.local", "Brand123!");
        byte[] logoBytes = "hi-a-fake-png".getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> presignResp = rest.exchange(
                "/brandpartner/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("purpose", "BRAND_LOGO", "contentType", "image/png",
                        "contentLength", logoBytes.length), auth(token)), Map.class);
        assertThat(presignResp.getStatusCode().is2xxSuccessful()).as("presign: %s", presignResp.getBody()).isTrue();
        String key = (String) presignResp.getBody().get("key");
        String uploadUrl = (String) presignResp.getBody().get("uploadUrl");

        putBytes(uploadUrl, "image/png", logoBytes);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> updateResp = rest.exchange(
                "/brandpartner/me", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("logoStorageKey", key), auth(token)), Map.class);
        assertThat(updateResp.getStatusCode().is2xxSuccessful()).as("update: %s", updateResp.getBody()).isTrue();
        assertThat(updateResp.getBody().get("logoUrl")).isEqualTo("https://cdn.it.local/" + key);
    }
}
