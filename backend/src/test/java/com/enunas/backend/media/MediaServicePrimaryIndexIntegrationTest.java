package com.enunas.backend.media;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.media.dto.ProductImageDto;
import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.UpdateProductImageDto;
import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link MediaService}'s primary-handling against the REAL V32 partial unique indexes on a
 * Testcontainers Postgres — the gap that let the write-order bugs ship. {@code MediaServiceTest}
 * mocks the repository and only sees that the service <em>called</em> the demote, never the SQL
 * ordering that decides 200 vs 500.
 */
@DataJpaTest
@ActiveProfiles("test")
class MediaServicePrimaryIndexIntegrationTest {

    @Autowired private ProductImageRepository imageRepository;
    @Autowired private ProductVideoRepository videoRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductColorRepository colorRepository;
    @Autowired private BrandPartnerRepository brandPartnerRepository;
    @Autowired private UserRepository userRepository;

    private MediaService mediaService;
    private User owner;
    private Product product;
    private ProductColor black;

    @BeforeEach
    void setUp() {
        MediaStorageProperties props = new MediaStorageProperties();
        props.setCdnBaseUrl("https://cdn.it.local");
        MediaStorageService storage = Mockito.mock(MediaStorageService.class); // verifyUploaded = no-op
        mediaService = new MediaService(imageRepository, videoRepository, productRepository,
                colorRepository, storage, new MediaUrlResolver(props));

        owner = userRepository.save(User.builder()
                .email("brand@it.local").password("x").role(Role.BRAND_PARTNER).enabled(true).build());
        BrandPartner brand = brandPartnerRepository.save(
                BrandPartner.builder().user(owner).brandName("Acme").slug("acme").build());
        product = productRepository.save(Product.builder()
                .name("Tee").slug("tee").brand(brand).creator(owner).build());
        black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLK").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
    }

    private long primaryCount(Long colorId) {
        return imageRepository.findForProductAndOptionalColour(product.getId(), colorId).stream()
                .filter(ProductImage::isPrimary).count();
    }

    private ProductImage seedImage(ProductColor colour, boolean primary, String name) {
        return imageRepository.saveAndFlush(ProductImage.builder()
                .product(product).productColor(colour)
                .storageKey("products/" + product.getId() + "/images/" + name)
                .primary(primary).build());
    }

    @Test
    void addImage_primaryIntoGroupThatAlreadyHasPrimary_succeeds() {
        ProductImage old = seedImage(black, true, "old.jpg");

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/" + product.getId() + "/images/new.jpg");
        dto.setProductColorId(black.getId());
        dto.setPrimary(true);

        ProductImageResponseDto resp = mediaService.addImage(product.getId(), dto, owner);
        imageRepository.flush();

        assertThat(resp.isPrimary()).isTrue();
        assertThat(imageRepository.findById(old.getId()).orElseThrow().isPrimary()).isFalse();
        assertThat(primaryCount(black.getId())).isEqualTo(1);
    }

    @Test
    void addImage_sharedPrimaryWhenSharedPrimaryExists_succeeds() {
        seedImage(null, true, "shared-old.jpg");

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/" + product.getId() + "/images/shared-new.jpg");
        dto.setPrimary(true);

        mediaService.addImage(product.getId(), dto, owner);
        imageRepository.flush();

        assertThat(primaryCount(null)).isEqualTo(1);
    }

    @Test
    void updateImage_promoteSiblingInSameGroup_succeeds() {
        // Seed the to-be-promoted row FIRST so it is the earlier persistence-context entry —
        // the order in which Hibernate emits the two UPDATEs at flush. Without the demote-and-flush
        // fix, the sibling's is_primary=true UPDATE would then reach the DB before the incumbent's
        // demotion and violate uq_product_images_primary_per_colour.
        ProductImage sibling = seedImage(black, false, "b.jpg");
        ProductImage cover = seedImage(black, true, "a.jpg");

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setPrimary(true);
        mediaService.updateImage(product.getId(), sibling.getId(), dto, owner);
        imageRepository.flush();

        assertThat(imageRepository.findById(cover.getId()).orElseThrow().isPrimary()).isFalse();
        assertThat(imageRepository.findById(sibling.getId()).orElseThrow().isPrimary()).isTrue();
        assertThat(primaryCount(black.getId())).isEqualTo(1);
    }

    @Test
    void updateImage_colourChangePlusPrimaryIntoOccupiedTargetGroup_succeeds() {
        ProductColor white = colorRepository.save(ProductColor.builder()
                .sku("SKUWHT").color("White").colorFamily(ColorFamily.WHITE).product(product).build());
        ProductImage blackCover = seedImage(black, true, "b.jpg");
        ProductImage whiteCover = seedImage(white, true, "w.jpg");

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setProductColorId(white.getId());
        dto.setPrimary(true);
        mediaService.updateImage(product.getId(), blackCover.getId(), dto, owner);
        imageRepository.flush();

        ProductImage moved = imageRepository.findById(blackCover.getId()).orElseThrow();
        assertThat(moved.getProductColor().getId()).isEqualTo(white.getId());
        assertThat(moved.isPrimary()).isTrue();
        assertThat(imageRepository.findById(whiteCover.getId()).orElseThrow().isPrimary()).isFalse();
        assertThat(primaryCount(white.getId())).isEqualTo(1);
    }
}
