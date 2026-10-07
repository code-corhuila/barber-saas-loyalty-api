package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.ConfigView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.LoyaltyCardView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.TransactionView;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.ConfigCommand;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the tags Rewards config and Cards: shapes are checked here, rules in the core. */
@RestController
@RequestMapping("/api/v1/loyalty")
public class CardsController {

    private static final Set<String> CONFIG_FIELDS = Set.of("stickersRequired", "rewardDescription", "isActive");

    private final LoyaltyUseCases loyalty;

    public CardsController(LoyaltyUseCases loyalty) {
        this.loyalty = loyalty;
    }

    @GetMapping("/config")
    public ConfigView config(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller) {
        return ConfigView.of(loyalty.config(caller));
    }

    /** setLoyaltyConfig: creates or replaces; isActive defaults to true. */
    @PutMapping("/config")
    public ConfigView setConfig(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                @RequestBody(required = false) JsonNode body) {
        JsonBody b = JsonBody.of(body, CONFIG_FIELDS);
        ConfigCommand command = new ConfigCommand(b.integer("stickersRequired", 1), b.text("rewardDescription", 255),
                b.bool("isActive", true));
        b.validate();
        return ConfigView.of(loyalty.setConfig(caller, command));
    }

    @GetMapping("/cards")
    public PageView<LoyaltyCardView> cards(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) UUID clientId,
                                           @RequestParam(required = false) Boolean canRedeem) {
        return PageView.of(loyalty.cards(caller, clientId, canRedeem, Requests.page(page, limit)), LoyaltyCardView::of);
    }

    @GetMapping("/cards/me")
    public LoyaltyCardView myCard(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller) {
        return LoyaltyCardView.of(loyalty.myCard(caller));
    }

    @GetMapping("/cards/{id}")
    public LoyaltyCardView card(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return LoyaltyCardView.of(loyalty.card(caller, id));
    }

    @GetMapping("/cards/{id}/transactions")
    public PageView<TransactionView> transactions(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                                  @PathVariable UUID id,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) String type) {
        return PageView.of(loyalty.transactions(caller, id, type(type), Requests.page(page, limit)), TransactionView::of);
    }

    private static TransactionType type(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TransactionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("the request is not valid", List.of(new FieldError("type", "not an accepted value")));
        }
    }
}
