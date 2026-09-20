package in.reconpilot.ingest;

import in.reconpilot.mdr.PayeeCategory;
import in.reconpilot.mdr.PaymentRail;
import in.reconpilot.mdr.TxnType;

import java.time.Instant;

/**
 * One parsed row from a settlement file.
 *
 * <p>Short-lived by design: a row is created, written, and becomes garbage.
 * Nothing accumulates a collection of these, which is what keeps memory use
 * independent of file size.
 */
public record SettlementRow(
        String externalTxnId,
        String merchantRef,
        long amountPaise,
        TxnType txnType,
        PaymentRail rail,
        PayeeCategory payeeCategory,
        long chargedMdrPaise,
        Instant occurredAt,
        String rawLine
) {}
