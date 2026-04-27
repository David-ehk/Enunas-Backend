package com.enunas.backend.order;

public enum ProblemType {
    OUT_OF_STOCK,        // Doch nicht auf Lager
    DAMAGED,             // Produkt beschädigt
    WRONG_ITEM,          // Falscher Artikel
    SHIPPING_DELAY,      // Versandverzögerung
    OTHER
}
