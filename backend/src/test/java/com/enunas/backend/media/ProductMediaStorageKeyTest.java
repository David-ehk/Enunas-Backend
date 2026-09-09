package com.enunas.backend.media;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class ProductMediaStorageKeyTest {

    @Autowired private ProductImageRepository imageRepository;
    @Autowired private ProductVideoRepository videoRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductColorRepository colorRepository;
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

    @Test
    void productImage_colourTag_persistsAndReadsBack() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLACK").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());

        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg")
                .displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow()
                .getProductColor().getId()).isEqualTo(black.getId());
    }

    @Test
    void productImage_colourTag_isOptional() {
        Product product = seedProduct();
        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).storageKey("products/" + product.getId() + "/images/s.jpg")
                .displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow().getProductColor()).isNull();
    }

    @Test
    void productImage_twoPrimariesInSameColourGroup_violateUniqueIndex() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLK2").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b1.jpg").primary(true).build());

        assertThatThrownBy(() -> imageRepository.saveAndFlush(ProductImage.builder()
                .product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b2.jpg").primary(true).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void productImage_twoSharedPrimaries_violateUniqueIndex() {
        Product product = seedProduct();
        imageRepository.saveAndFlush(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s1.jpg").primary(true).build());

        assertThatThrownBy(() -> imageRepository.saveAndFlush(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s2.jpg").primary(true).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void productImage_primaryInDifferentColourGroups_isAllowed() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLK3").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        ProductColor white = colorRepository.save(ProductColor.builder()
                .sku("SKUWHT3").color("White").colorFamily(ColorFamily.WHITE).product(product).build());

        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").primary(true).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(white)
                .storageKey("products/" + product.getId() + "/images/w.jpg").primary(true).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/shared.jpg").primary(true).build());

        assertThat(imageRepository.findByProductIdOrderByDisplayOrderAsc(product.getId())).hasSize(3);
    }

    @Test
    void findForProduct_noColourFilter_returnsAll() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUF1").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").displayOrder(1).build());
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").displayOrder(0).build());

        assertThat(imageRepository.findForProductAndOptionalColour(product.getId(), null)).hasSize(2);
    }

    @Test
    void findForProduct_withColourFilter_returnsTaggedPlusShared_notOtherColours() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUF2B").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        ProductColor white = colorRepository.save(ProductColor.builder()
                .sku("SKUF2W").color("White").colorFamily(ColorFamily.WHITE).product(product).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").displayOrder(0).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(white)
                .storageKey("products/" + product.getId() + "/images/w.jpg").displayOrder(0).build());
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").displayOrder(0).build());

        List<ProductImage> forBlack =
                imageRepository.findForProductAndOptionalColour(product.getId(), black.getId());

        assertThat(forBlack).extracting(i -> i.getStorageKey().substring(i.getStorageKey().lastIndexOf('/') + 1))
                .containsExactlyInAnyOrder("b.jpg", "s.jpg");
    }

    @Test
    void findByProductIdAndProductColorIdAndPrimary_nullColour_matchesSharedGroup() {
        Product product = seedProduct();
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").primary(true).build());

        assertThat(imageRepository.findByProductIdAndProductColorIdAndPrimary(product.getId(), null, true))
                .isPresent();
    }
}
