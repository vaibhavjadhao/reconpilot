package in.reconpilot.dispute;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    /** Raise a draft claim for a break. */
    @PostMapping
    public DisputeView create(@RequestParam UUID breakId,
                              @RequestParam(defaultValue = "analyst") String actor) {
        return disputes.createFor(breakId, actor);
    }

    /**
     * The only way to change a dispute's state.
     *
     * <p>Deliberately one endpoint rather than /file, /accept, /reject and so
     * on. Separate endpoints tempt each one to do its own state checking, and
     * the checks drift apart. One gate, one set of rules.
     */
    @PostMapping("/{id}/transition")
    public DisputeView transition(@PathVariable UUID id,
                                  @RequestParam DisputeStatus target,
                                  @RequestParam(defaultValue = "analyst") String actor,
                                  @RequestParam(required = false) String note,
                                  @RequestParam(required = false) Long recoveredPaise) {
        return disputes.transition(id, target, actor, note, recoveredPaise);
    }

    @GetMapping("/{id}")
    public DisputeView get(@PathVariable UUID id) {
        return disputes.find(id);
    }

    /** Who did what, and when. The question asked six months later. */
    @GetMapping("/{id}/history")
    public List<DisputeEventView> history(@PathVariable UUID id) {
        return disputes.history(id);
    }

    @GetMapping
    public List<DisputeView> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "50") int limit) {
        return disputes.list(status, limit);
    }

    /** Claimed versus actually recovered. Only the second is real money. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return disputes.summary();
    }
}
