package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import java.util.List;

/** Shape checks shared by every controller: paging and the Idempotency-Key header. */
final class Requests {

    private Requests() {
    }

    /** PageParam and LimitParam of _shared.yaml: page ≥ 1, 1 ≤ limit ≤ 100, defaults 1 and 20. */
    static Page.Request page(Integer page, Integer limit) {
        int p = page == null ? 1 : page;
        int l = limit == null ? 20 : limit;
        if (p < 1 || l < 1 || l > 100) {
            throw new ValidationException("the request is not valid",
                    List.of(new FieldError("page/limit", "page >= 1 and 1 <= limit <= 100")));
        }
        return new Page.Request(p, l);
    }

    /** IdempotencyKeyHeader: required on every creation, between 8 and 128 characters. */
    static String idempotencyKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 128) {
            throw new ValidationException("the request is not valid",
                    List.of(new FieldError("Idempotency-Key", "required, between 8 and 128 characters")));
        }
        return key;
    }
}
