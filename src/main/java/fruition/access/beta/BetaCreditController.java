package fruition.access.beta;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BetaCreditController {
    private final BetaCreditService credits;
    public BetaCreditController(BetaCreditService credits) { this.credits = credits; }

    @GetMapping("/api/auth/me/beta-credit")
    public BetaCreditService.Status status(@AuthenticationPrincipal String userId) {
        return credits.status(userId);
    }
}
