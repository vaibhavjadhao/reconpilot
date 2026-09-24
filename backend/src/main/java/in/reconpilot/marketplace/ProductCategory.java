package in.reconpilot.marketplace;

/**
 * Product category, which sets the commission rate.
 *
 * <p>Deliberately a short list. Amazon alone publishes rates for more than
 * 1,800 categories, and inventing the ones we cannot cite would defeat the
 * point of the exercise: this tool exists to be independently right about
 * money. A category with no cited rate raises
 * {@link UnpublishedRateException} rather than guessing, exactly as the MDR
 * engine does for the education category.
 */
public enum ProductCategory {
    MOBILE_PHONES,
    FASHION_JEWELLERY,
    APPAREL,
    FOOTWEAR,
    HOME_AND_KITCHEN,
    BEAUTY_AND_PERSONAL_CARE,
    GROCERY,
    TOYS_AND_GAMES,
    /** Present so that an unmapped category is representable and refuses loudly. */
    UNKNOWN
}
