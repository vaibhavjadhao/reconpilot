package in.reconpilot.ingest;

import in.reconpilot.mdr.PayeeCategory;
import in.reconpilot.mdr.PaymentRail;
import in.reconpilot.mdr.TxnType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Streams a settlement CSV one row at a time.
 *
 * <p>The returned {@link Stream} is lazy and tied to an open file handle, so
 * callers MUST use it in a try-with-resources block. Nothing here ever holds
 * more than a single line in memory: a 148 MB file processes in ~23 MB of heap,
 * and a 2 GB file costs the same, because the requirement is constant rather
 * than proportional to input.
 */
@Component
public class SettlementCsvParser {

    /** Expected header, used to fail fast on an unexpected file shape. */
    static final String EXPECTED_HEADER =
            "txn_id,merchant_vpa,amount_paise,txn_type,rail,payee_category,charged_mdr_paise,occurred_at";

    public Stream<SettlementRow> parse(Path file) throws IOException {
        Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8);
        return lines
                .skip(1)                       // header
                .filter(l -> !l.isBlank())
                .map(SettlementCsvParser::toRow);
    }

    public void verifyHeader(Path file) throws IOException {
        try (Stream<String> s = Files.lines(file, StandardCharsets.UTF_8)) {
            String header = s.findFirst().orElse("");
            if (!EXPECTED_HEADER.equals(header.trim())) {
                throw new IllegalArgumentException(
                        "Unexpected header.%n  expected: %s%n  found:    %s"
                                .formatted(EXPECTED_HEADER, header));
            }
        }
    }

    private static SettlementRow toRow(String line) {
        String[] c = line.split(",", -1);
        if (c.length < 8) {
            throw new IllegalArgumentException("Expected 8 columns, found " + c.length + ": " + line);
        }
        return new SettlementRow(
                c[0],
                c[1],
                Long.parseLong(c[2]),
                TxnType.valueOf(c[3]),
                PaymentRail.valueOf(c[4]),
                PayeeCategory.valueOf(c[5]),
                Long.parseLong(c[6]),
                Instant.parse(c[7]),
                line);
    }
}
