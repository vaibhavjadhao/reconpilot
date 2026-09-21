package in.reconpilot.format;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Asks Claude to map an unfamiliar settlement file onto our canonical fields.
 *
 * <h2>Where the model is allowed to act, and where it is not</h2>
 *
 * The model reads column headers and a handful of example values and proposes
 * which column means what. That is the whole job. It never sees a computed fee,
 * never decides whether something is a break, and never produces a number that
 * reaches a customer.
 *
 * <p>This boundary is deliberate. The rest of the system is built to be
 * deterministic, replayable and paisa-exact; a non-deterministic component in
 * the calculation path would undo all of it. Mapping unknown column names is
 * the one part of the problem whose input space is genuinely open-ended, which
 * is precisely where a model earns its place and a regular expression does not.
 *
 * <h2>Cost</h2>
 *
 * Discovery runs <b>once per format</b>, keyed on a fingerprint of the header
 * row, and the result is stored. A million-row file costs one call the first
 * time and none afterwards.
 */
@Service
public class FormatDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(FormatDiscoveryService.class);

    private static final String MODEL = "claude-opus-5";

    private static final String SYSTEM = """
            You map columns in payment settlement files onto a fixed set of canonical fields.

            You will be given a file's header row and a few example rows. Identify which column
            corresponds to each canonical field, state whether amounts are in rupees or paise, and
            provide translations for any coded values.

            Rules:
            - Copy source column names EXACTLY as they appear, including spacing and punctuation.
            - Omit any canonical field the file does not contain. Do not invent a mapping.
            - Report confidence honestly. A low score on an ambiguous column is far more useful
              than a confident guess, because a human reviews low-confidence mappings and an
              incorrect high-confidence one is acted on silently.
            - Amounts: PAISE means whole paise with no decimal part. RUPEES means rupees, which
              may carry decimals. Getting this wrong is a factor-of-100 error in every figure,
              so judge it from the example values rather than from the column name.
            - Value aliases translate what the file says into our vocabulary, for example a rail
              column containing "QR" maps to UPI_QR.

            Canonical values:
              TXN_TYPE:        P2P, P2M, P2PM
              RAIL:            UPI_QR, UPI_INTENT, UPI_AUTOPAY, UPI_CREDIT_LINE
              PAYEE_CATEGORY:  STANDARD, CAPITAL_MARKETS, INDUSTRY_PROGRAM, EDUCATION
            """;

    private final boolean enabled;
    private final int sampleRows;
    private AnthropicClient client;

    public FormatDiscoveryService(
            @Value("${reconpilot.ai.format-discovery.enabled:true}") boolean enabled,
            @Value("${reconpilot.ai.format-discovery.sample-rows:5}") int sampleRows) {
        this.enabled = enabled;
        this.sampleRows = sampleRows;
    }

    public MappingProposal propose(List<String> header, List<Map<String, String>> rows) {
        if (!enabled) {
            throw new FormatDiscoveryException(
                    "Format discovery is disabled (reconpilot.ai.format-discovery.enabled=false). "
                  + "Map this format by hand, or enable discovery.");
        }

        StructuredMessageCreateParams<MappingProposal> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16_000L)
                // Adaptive thinking: working out that a column called "Amt (Rs.)" is rupees
                // while "settlement_value" is paise is genuine reasoning, not pattern matching.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM)
                .outputConfig(MappingProposal.class)
                .addUserMessage(buildPrompt(header, rows))
                .build();

        try {
            Optional<MappingProposal> proposal = client().messages().create(params).content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(typed -> typed.text())
                    .findFirst();

            return proposal.orElseThrow(() -> new FormatDiscoveryException(
                    "The model returned no mapping. The file header may be unreadable."));

        } catch (UnauthorizedException e) {
            // The SDK builds a client happily without a key and only fails on the
            // first call, so this is where a missing credential actually shows up.
            // Passing Anthropic's raw JSON through would send whoever sees it
            // looking at their own login rather than at the server's config.
            throw new FormatDiscoveryException(
                    "Anthropic rejected the request as unauthenticated. Set ANTHROPIC_API_KEY on "
                  + "the server, or disable discovery with "
                  + "reconpilot.ai.format-discovery.enabled=false and map formats by hand.", e);
        } catch (RateLimitException e) {
            throw new FormatDiscoveryException("Rate limited while discovering the format. Retry shortly.", e);
        } catch (AnthropicServiceException e) {
            throw new FormatDiscoveryException("Format discovery failed: " + e.getMessage(), e);
        }
    }

    /**
     * Built lazily and cached, so a missing API key surfaces when discovery is
     * actually attempted rather than preventing the application from starting.
     * Everything else in ReconPilot works without it.
     */
    private synchronized AnthropicClient client() {
        if (client == null) {
            try {
                client = AnthropicOkHttpClient.fromEnv();
            } catch (RuntimeException e) {
                throw new FormatDiscoveryException(
                        "No Anthropic credentials found. Set ANTHROPIC_API_KEY, or disable "
                      + "discovery with reconpilot.ai.format-discovery.enabled=false and map "
                      + "formats by hand.", e);
            }
        }
        return client;
    }

    String buildPrompt(List<String> header, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Header row:\n").append(String.join(" | ", header)).append("\n\nExample rows:\n");

        rows.stream().limit(sampleRows).forEach(row -> {
            sb.append(header.stream()
                    .map(h -> String.valueOf(row.getOrDefault(h, "")))
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(""));
            sb.append('\n');
        });

        sb.append("\nCanonical fields:\n");
        for (CanonicalField f : CanonicalField.values()) {
            sb.append("  ").append(f.name()).append(" - ").append(f.description()).append('\n');
        }
        return sb.toString();
    }

    public String model() {
        return MODEL;
    }
}
