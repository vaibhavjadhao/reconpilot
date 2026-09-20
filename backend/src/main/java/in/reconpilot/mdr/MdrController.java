package in.reconpilot.mdr;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to the MDR calculation.
 *
 * <p>Deliberately thin: it converts query parameters into an {@link MdrInput},
 * delegates, and returns the result. All the rules live in
 * {@link MdrCalculator}, which knows nothing about HTTP.
 */
@RestController
public class MdrController {

    private final MdrCalculator calculator;

    /** Constructor injection: one constructor, so Spring supplies the bean. */
    public MdrController(MdrCalculator calculator) {
        this.calculator = calculator;
    }

    @GetMapping("/api/mdr")
    public MdrResult calculate(
            @RequestParam long amountPaise,
            @RequestParam TxnType txnType,
            @RequestParam PaymentRail rail,
            @RequestParam PayeeCategory payeeCategory) {

        return calculator.calculate(
                new MdrInput(amountPaise, txnType, rail, payeeCategory));
    }
}
