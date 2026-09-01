/**
 * Persistence-internal JPA mappings for the Buying Case aggregate.
 *
 * <p>The aggregate root is optimistically versioned. Consent changes and report-version allocation
 * take a pessimistic root lock. Child metadata stores scalar case identifiers, has no entity graph,
 * and therefore cannot lazy-fetch or cascade changes into the aggregate or immutable reports.
 * Transaction boundaries live on the persistence facade methods.
 */
package com.dealanalyzer.buyingcase.persistence;
