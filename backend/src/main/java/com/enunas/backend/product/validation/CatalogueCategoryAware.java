package com.enunas.backend.product.validation;

import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;

import java.util.List;

public interface CatalogueCategoryAware {
    ProductCategory getCategory();
    List<ProductCatalogueCategory> getCatalogueCategory();
}
