package com.example.inventory.product;

import com.example.inventory.product.api.CreateProductRequest;
import com.example.inventory.product.api.PatchProductRequest;
import com.example.inventory.product.api.ProductResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repository;

    @Test
    void createsNormalizedProduct() {
        when(repository.existsBySkuIgnoreCase("PHONE-15")).thenReturn(false);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProductService service = new ProductService(repository);

        ProductResponse response = service.create(new CreateProductRequest(
                " phone-15 ", "iPhone 15", "Phone", " Electronics ",
                new BigDecimal("799.00"), 12, true));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSku()).isEqualTo("PHONE-15");
        assertThat(captor.getValue().getCategory()).isEqualTo("Electronics");
        assertThat(response.id()).isNotNull();
    }

    @Test
    void rejectsDuplicateSku() {
        when(repository.existsBySkuIgnoreCase("DUPLICATE")).thenReturn(true);
        ProductService service = new ProductService(repository);

        assertThatThrownBy(() -> service.create(new CreateProductRequest(
                "duplicate", "Title", null, "Category", BigDecimal.ONE, 1, true)))
                .isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void rejectsStalePatchVersion() {
        UUID id = UUID.randomUUID();
        Product product = new Product(id, "SKU", "Title", null, "Category", BigDecimal.ONE, 1, true);
        when(repository.findById(id)).thenReturn(Optional.of(product));
        ProductService service = new ProductService(repository);

        PatchProductRequest request = new PatchProductRequest(
                "Changed", null, null, null, null, null, 4L);

        assertThatThrownBy(() -> service.patch(id, request))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void unsetsDescriptionWhenExplicitlyProvidedAsNull() {
        UUID id = UUID.randomUUID();
        Product product = new Product(id, "SKU", "Title", "Existing Description", "Category", BigDecimal.ONE, 1, true);
        when(repository.findById(id)).thenReturn(Optional.of(product));
        when(repository.saveAndFlush(product)).thenAnswer(inv -> inv.getArgument(0));
        ProductService service = new ProductService(repository);

        PatchProductRequest request = new PatchProductRequest(
                null, null, null, null, null, null, null, java.util.Set.of("description"));

        ProductResponse response = service.patch(id, request);
        assertThat(response.description()).isNull();
        assertThat(product.getDescription()).isNull();
    }

    @Test
    void preservesDescriptionWhenOmittedInPatch() {
        UUID id = UUID.randomUUID();
        Product product = new Product(id, "SKU", "Title", "Keep this description", "Category", BigDecimal.ONE, 1, true);
        when(repository.findById(id)).thenReturn(Optional.of(product));
        when(repository.saveAndFlush(product)).thenAnswer(inv -> inv.getArgument(0));
        ProductService service = new ProductService(repository);

        PatchProductRequest request = new PatchProductRequest(
                "New Title", null, null, null, null, null, null, java.util.Set.of("title"));

        ProductResponse response = service.patch(id, request);
        assertThat(response.title()).isEqualTo("New Title");
        assertThat(response.description()).isEqualTo("Keep this description");
        assertThat(product.getDescription()).isEqualTo("Keep this description");
    }
}

