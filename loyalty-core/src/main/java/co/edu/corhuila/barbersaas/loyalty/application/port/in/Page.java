package co.edu.corhuila.barbersaas.loyalty.application.port.in;

import java.util.List;
import java.util.function.Function;

/** One page of a collection ({data, meta} of _shared.yaml, norm 5.3.6). */
public record Page<T>(List<T> items, int page, int limit, long total) {

    /** page starts at 1; limit between 1 and 100 (the HTTP adapter rejects anything else with 400). */
    public record Request(int page, int limit) {
        public Request {
            if (page < 1 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("page >= 1 and 1 <= limit <= 100");
            }
        }

        public int offset() {
            return (page - 1) * limit;
        }
    }

    public Page {
        items = List.copyOf(items);
    }

    public static <T> Page<T> of(List<T> all, Request r) {
        int from = Math.min(r.offset(), all.size());
        int to = Math.min(from + r.limit(), all.size());
        return new Page<>(all.subList(from, to), r.page(), r.limit(), all.size());
    }

    public long totalPages() {
        return (total + limit - 1) / limit;
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(items.stream().map(f).toList(), page, limit, total);
    }
}
