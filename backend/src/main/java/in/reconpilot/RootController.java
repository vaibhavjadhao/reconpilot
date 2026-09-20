package in.reconpilot;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A human-readable index at the root path.
 *
 * <p>An API has no homepage unless one is written. This exists so the service
 * is explorable in a browser during development rather than requiring long
 * URLs to be assembled by hand.
 */
@RestController
public class RootController {

    private static final String BASE = "/api/mdr?amountPaise=%d&txnType=%s&rail=%s&payeeCategory=%s";

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>ReconPilot</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
                           max-width: 820px; margin: 0 auto; padding: 32px 16px; line-height: 1.55; }
                    h1 { margin-bottom: 4px; font-size: 1.5rem; }
                    .sub { opacity: .7; margin-top: 0; font-size: .95rem; }
                    h2 { font-size: 1rem; margin-top: 28px; border-bottom: 1px solid currentColor;
                         padding-bottom: 6px; opacity: .85; }
                    ul { list-style: none; padding: 0; }
                    li { padding: 7px 0; display: flex; gap: 12px; flex-wrap: wrap; align-items: baseline; }
                    a { text-decoration: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                        font-size: .88rem; }
                    a:hover { text-decoration: underline; }
                    .want { opacity: .65; font-size: .85rem; }
                  </style>
                </head>
                <body>
                  <h1>ReconPilot</h1>
                  <p class="sub">UPI MDR verification &middot; rules effective 15 October 2026</p>
                """);

        html.append("<h2>Figures stated by the regulator</h2><ul>");
        link(html,   200_000, "P2M", "UPI_QR", "STANDARD",        "&#8377;2,000 &rarr; &#8377;0 (at threshold)");
        link(html,   300_000, "P2M", "UPI_QR", "STANDARD",        "&#8377;3,000 &rarr; &#8377;12  (FAQ Q35)");
        link(html, 5_000_000, "P2M", "UPI_QR", "STANDARD",        "&#8377;50,000 &rarr; &#8377;200  (FAQ Q35)");
        link(html, 7_500_000, "P2M", "UPI_QR", "STANDARD",        "&#8377;75,000 &rarr; &#8377;300, cap binds  (FAQ Q35)");
        link(html,10_000_000, "P2M", "UPI_QR", "STANDARD",        "&#8377;1,00,000 &rarr; &#8377;300, not &#8377;400  (FAQ Q32)");
        html.append("</ul>");

        html.append("<h2>Exemptions</h2><ul>");
        link(html,50_000_000, "P2PM","UPI_QR", "STANDARD",        "P2PM merchant, &#8377;5,00,000 &rarr; &#8377;0");
        link(html, 1_000_000, "P2M", "UPI_AUTOPAY","STANDARD",    "AutoPay / mandate &rarr; &#8377;0  (FAQ Q22)");
        link(html,   500_000, "P2P", "UPI_QR", "STANDARD",        "Person to person &rarr; &#8377;0  (FAQ Q16)");
        html.append("</ul>");

        html.append("<h2>Other categories</h2><ul>");
        link(html, 5_000_000, "P2M","UPI_QR","CAPITAL_MARKETS",   "Capital markets, &#8377;50,000 &rarr; &#8377;10 (0.02%)");
        link(html,10_000_000, "P2M","UPI_QR","INDUSTRY_PROGRAM",  "Fuel / telecom / insurance &rarr; &#8377;5 flat");
        html.append("</ul>");

        html.append("<h2>Rules we refuse to guess</h2><ul>");
        link(html,   500_000, "P2M","UPI_QR","EDUCATION",         "501 &mdash; no rate published  (FAQ Q42)");
        link(html,   500_000, "P2M","UPI_CREDIT_LINE","STANDARD", "501 &mdash; card rules, not this framework  (FAQ Q36)");
        html.append("</ul>");

        html.append("<h2>Bad input</h2><ul>");
        link(html,        -5, "P2M","UPI_QR","STANDARD",          "400 &mdash; negative amount");
        link(html,   300_000, "BANANA","UPI_QR","STANDARD",       "400 &mdash; not a valid txnType");
        html.append("</ul>");

        html.append("""
                  <h2>Operations</h2>
                  <ul>
                    <li><a href="/actuator/health">/actuator/health</a> <span class="want">service health</span></li>
                    <li><a href="/actuator">/actuator</a> <span class="want">available endpoints</span></li>
                  </ul>
                  <p class="sub">Amounts are in paise. &#8377;1 = 100 paise.</p>
                </body></html>
                """);
        return html.toString();
    }

    private static void link(StringBuilder sb, long paise, String type, String rail, String cat, String want) {
        String url = BASE.formatted(paise, type, rail, cat);
        sb.append("<li><a href=\"").append(url).append("\">")
          .append(type).append(" &middot; ").append(paise).append(" paise")
          .append("</a><span class=\"want\">").append(want).append("</span></li>");
    }
}
