package in.reconpilot.recon;

import java.util.UUID;

/**
 * Everything a client needs to render one break and decide what may be done
 * with it.
 *
 * <p>Replaces the raw {@code Map<String,Object>} the endpoint returned before.
 * That shape had two problems: it leaked the database's snake_case naming into
 * the API, and it omitted the break's own id -- so a client could display a
 * finding but could not act on it.
 *
 * <p>{@code disputeId} and {@code disputeStatus} are null until a claim exists.
 * Including them here means a list view can decide whether to offer "raise a
 * claim" without a second request per row.
 */
public record BreakView(
        UUID id,
        String breakType,
        String status,
        long expectedMdrPaise,
        long chargedMdrPaise,
        long deltaPaise,
        String externalTxnId,
        long amountPaise,
        String txnType,
        String paymentRail,
        String payeeCategory,
        UUID disputeId,
        String disputeStatus
) {}
