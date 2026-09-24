package dev.costinfl.outflow.merchant.normalize;

/** One step of merchant normalization (DESIGN: Merchant normalization). Pure, total, never returns null. */
@FunctionalInterface
public interface MerchantStep {

    String apply(String text);
}
