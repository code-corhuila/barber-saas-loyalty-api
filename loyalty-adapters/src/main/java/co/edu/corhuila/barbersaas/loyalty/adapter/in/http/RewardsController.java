package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.CouponView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.PageView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.RedemptionResultView;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.Views.StickerResultView;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Caller;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Created;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.RedemptionResult;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.LoyaltyUseCases.StickerResult;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP to use case for the tags Stickers and redemptions, and Coupons. */
@RestController
@RequestMapping("/api/v1/loyalty")
public class RewardsController {

    private static final Set<String> STICKER_FIELDS = Set.of("clientId", "appointmentId");
    private static final Set<String> REDEEM_FIELDS = Set.of("clientId");
    private static final Set<String> USE_FIELDS = Set.of("appointmentId");

    private final LoyaltyUseCases loyalty;

    public RewardsController(LoyaltyUseCases loyalty) {
        this.loyalty = loyalty;
    }

    /** grantSticker: 201 with the card's Location, or 200 when the same Idempotency-Key is retried. */
    @PostMapping("/stickers")
    public ResponseEntity<StickerResultView> sticker(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @RequestBody(required = false) JsonNode body) {
        String idempotencyKey = Requests.idempotencyKey(key);
        JsonBody b = JsonBody.of(body, STICKER_FIELDS);
        UUID clientId = b.uuid("clientId");
        UUID appointmentId = b.optionalUuid("appointmentId");
        b.validate();
        Created<StickerResult> result = loyalty.grantSticker(caller, clientId, appointmentId, idempotencyKey);
        StickerResultView view = StickerResultView.of(result.value());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/loyalty/cards/" + view.card().id())).body(view)
                : ResponseEntity.ok(view);
    }

    /** redeemReward: 201 with the coupon's Location, or 200 when retried (nothing is subtracted again). */
    @PostMapping("/redemptions")
    public ResponseEntity<RedemptionResultView> redeem(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @RequestBody(required = false) JsonNode body) {
        String idempotencyKey = Requests.idempotencyKey(key);
        JsonBody b = JsonBody.of(body, REDEEM_FIELDS);
        UUID clientId = b.uuid("clientId");
        b.validate();
        Created<RedemptionResult> result = loyalty.redeem(caller, clientId, idempotencyKey);
        RedemptionResultView view = RedemptionResultView.of(result.value());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/loyalty/coupons/" + view.coupon().id())).body(view)
                : ResponseEntity.ok(view);
    }

    @GetMapping("/coupons")
    public PageView<CouponView> coupons(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(required = false) UUID clientId,
                                        @RequestParam(required = false) String status) {
        return PageView.of(loyalty.coupons(caller, clientId, status(status), Requests.page(page, limit)), CouponView::of);
    }

    @GetMapping("/coupons/{id}")
    public CouponView coupon(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id) {
        return CouponView.of(loyalty.coupon(caller, id));
    }

    @PostMapping("/coupons/{id}/use")
    public CouponView use(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller, @PathVariable UUID id,
                          @RequestBody(required = false) JsonNode body) {
        JsonBody b = JsonBody.of(body, USE_FIELDS);
        UUID appointmentId = b.uuid("appointmentId");
        b.validate();
        return CouponView.of(loyalty.useCoupon(caller, id, appointmentId));
    }

    private static CouponStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CouponStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("the request is not valid", List.of(new FieldError("status", "not an accepted value")));
        }
    }
}
