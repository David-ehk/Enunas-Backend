package com.enunas.backend.media;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class ProductMediaStorageKeyTest {

    @Autowired private ProductImageRepository imageRepository;
    @Autowired private ProductVideoRepository videoRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BrandPartnerRepository brandPartnerRepository;
    @Autowired private UserRepository userRepository;

    private Product seedProduct() {
        User user = userRepository.save(User.builder()
                .email("brand@it.local").password("x").role(Role.BRAND_PARTNER).enabled(true).build());
        BrandPartner brand = brandPartnerRepository.save(
                BrandPartner.builder().user(user).brandName("Acme").slug("acme").build());
        return productRepository.save(Product.builder()
                .name("Tee").slug("tee").brand(brand).creator(user).build());
    }

    @Test
    void savesAndReadsProductImageStorageKey() {
        Product product = seedProduct();
        String key = "products/" + product.getId() + "/images/abc.jpg";
        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).storageKey(key).primary(true).displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow().getStorageKey()).isEqualTo(key);
    }

    @Test
    void productImage_nullStorageKey_violatesNotNullConstraint() {
        Product product = seedProduct();
        assertThatThrownBy(() -> imageRepository.saveAndFlush(
                ProductImage.builder().product(product).storageKey(null).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesAndReadsProductVideoStorageKeys() {
        Product product = seedProduct();
        String key = "products/" + product.getId() + "/videos/abc.mp4";
        String thumbKey = "products/" + product.getId() + "/videos/abc-t.jpg";
        ProductVideo saved = videoRepository.save(ProductVideo.builder()
                .product(product).storageKey(key).thumbnailStorageKey(thumbKey).build());

        ProductVideo found = videoRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStorageKey()).isEqualTo(key);
        assertThat(found.getThumbnailStorageKey()).isEqualTo(thumbKey);
    }

    @Test
    void brandPartner_logoAndHeroStorageKeys_areNullableAndPersist() {
        User user = userRepository.save(User.builder()
                .email("brand2@it.local").password("x").role(Role.BRAND_PARTNER).enabled(true).build());
        BrandPartner brand = brandPartnerRepository.save(BrandPartner.builder()
                .user(user).brandName("Beta").slug("beta").build());
        assertThat(brand.getLogoStorageKey()).isNull();
        assertThat(brand.getHeroStorageKey()).isNull();

        brand.setLogoStorageKey("brands/" + brand.getId() + "/logo/abc.png");
        brand.setHeroStorageKey("brands/" + brand.getId() + "/hero/def.jpg");
        BrandPartner saved = brandPartnerRepository.save(brand);

        BrandPartner found = brandPartnerRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getLogoStorageKey()).isEqualTo("brands/" + brand.getId() + "/logo/abc.png");
        assertThat(found.getHeroStorageKey()).isEqualTo("brands/" + brand.getId() + "/hero/def.jpg");
    }
}
