package com.enunas.backend.product;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.dto.ProductVariantDto;
import com.enunas.backend.product.dto.ProductVariantResponseDto;
import com.enunas.backend.product.dto.UpdateProductVariantDto;
import com.enunas.backend.product.productvariant.*;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    @Mock private ProductVariantRepository variantRepository;
    @Mock private ProductColorRepository productColorRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private ProductVariantService service;

    private User creator;
    private Product product;

    @BeforeEach
    void setUp() {
        creator = User.builder().id(1L).email("brand@test.com").build();
        product = Product.builder().id(10L).name("Test Product").creator(creator).build();
        simulatePrePersist(product);
    }

    // ===== addVariant =====

    @Test
    void addVariant_newColor_createsProductColorAndVariant() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(productColorRepository.findByProductIdAndColor(10L, "Black")).thenReturn(Optional.empty());
        when(productColorRepository.existsBySku(anyString())).thenReturn(false);

        ProductColor savedColor = ProductColor.builder().id(1L).sku("ABCD1234").color("Black").product(product).build();
        when(productColorRepository.save(any())).thenReturn(savedColor);

        ProductVariant savedVariant = ProductVariant.builder()
                .id(100L).productColor(savedColor).size("M").stockQuantity(5).product(product).build();
        when(variantRepository.existsByProductColorIdAndSize(1L, "M")).thenReturn(false);
        when(variantRepository.save(any())).thenReturn(savedVariant);

        ProductVariantDto dto = new ProductVariantDto();
        dto.setColor("Black");
        dto.setSize("M");
        dto.setStockQuantity(5);

        ProductVariantResponseDto result = service.addVariant(10L, dto, creator);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getColor()).isEqualTo("Black");
        assertThat(result.getSku()).isEqualTo("ABCD1234");
        verify(productColorRepository).save(any(ProductColor.class));
        verify(variantRepository).save(any(ProductVariant.class));
    }

    @Test
    void addVariant_existingColor_reusesProductColor() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        ProductColor existingColor = ProductColor.builder().id(2L).sku("EXISTING").color("Blue").product(product).build();
        when(productColorRepository.findByProductIdAndColor(10L, "Blue")).thenReturn(Optional.of(existingColor));
        when(variantRepository.existsByProductColorIdAndSize(2L, "L")).thenReturn(false);

        ProductVariant savedVariant = ProductVariant.builder()
                .id(101L).productColor(existingColor).size("L").stockQuantity(3).product(product).build();
        when(variantRepository.save(any())).thenReturn(savedVariant);

        ProductVariantDto dto = new ProductVariantDto();
        dto.setColor("Blue");
        dto.setSize("L");
        dto.setStockQuantity(3);

        service.addVariant(10L, dto, creator);

        verify(productColorRepository, never()).save(any());  // no new color created
        verify(variantRepository).save(any());
    }

    @Test
    void addVariant_duplicateColorSize_throwsException() {
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        ProductColor existingColor = ProductColor.builder().id(3L).sku("SKU1").color("Red").product(product).build();
        when(productColorRepository.findByProductIdAndColor(10L, "Red")).thenReturn(Optional.of(existingColor));
        when(variantRepository.existsByProductColorIdAndSize(3L, "S")).thenReturn(true);

        ProductVariantDto dto = new ProductVariantDto();
        dto.setColor("Red");
        dto.setSize("S");

        assertThatThrownBy(() -> service.addVariant(10L, dto, creator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void addVariant_wrongOwner_throwsSecurityException() {
        User otherUser = User.builder().id(99L).email("other@test.com").build();
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        ProductVariantDto dto = new ProductVariantDto();
        dto.setColor("Green");
        dto.setSize("M");

        assertThatThrownBy(() -> service.addVariant(10L, dto, otherUser))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void addVariant_productNotFound_throwsProductNotFoundException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        ProductVariantDto dto = new ProductVariantDto();
        dto.setColor("Black");
        dto.setSize("M");

        assertThatThrownBy(() -> service.addVariant(99L, dto, creator))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // ===== generateUniqueSku =====

    @Test
    void generateUniqueSku_returnsEightCharUppercase() {
        when(productColorRepository.existsBySku(anyString())).thenReturn(false);
        String sku = service.generateUniqueSku();
        assertThat(sku).hasSize(8).matches("[A-Z0-9]{8}");
    }

    @Test
    void generateUniqueSku_retriesOnCollision() {
        when(productColorRepository.existsBySku(anyString()))
                .thenReturn(true)   // first attempt collides
                .thenReturn(false); // second attempt succeeds
        String sku = service.generateUniqueSku();
        assertThat(sku).hasSize(8);
        verify(productColorRepository, times(2)).existsBySku(anyString());
    }

    // ===== helpers =====

    private void simulatePrePersist(Product p) {
        try {
            var m = Product.class.getDeclaredMethod("onCreate");
            m.setAccessible(true);
            m.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
