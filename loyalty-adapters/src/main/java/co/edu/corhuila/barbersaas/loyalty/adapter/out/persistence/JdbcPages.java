package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** One page with LIMIT/OFFSET and the total of the same filter, as {data, meta} needs. */
final class JdbcPages {

    /** The FROM/WHERE part with its arguments, and the ORDER BY part with its own. Values are always bound. */
    record Query(String from, List<Object> args, String order, List<Object> orderArgs) {
        Query(String from, List<Object> args, String order) {
            this(from, args, order, List.of());
        }
    }

    private JdbcPages() {
    }

    static <T> Page<T> page(JdbcTemplate jdbc, String columns, Query q, RowMapper<T> mapper, Page.Request page) {
        Long total = jdbc.queryForObject("SELECT count(*) " + q.from(), Long.class, q.args().toArray());
        List<Object> args = new ArrayList<>(q.args());
        args.addAll(q.orderArgs());
        args.add(page.limit());
        args.add(page.offset());
        List<T> items = jdbc.query("SELECT " + columns + " " + q.from() + " " + q.order() + " LIMIT ? OFFSET ?",
                mapper, args.toArray());
        return new Page<>(items, page.page(), page.limit(), total == null ? 0 : total);
    }
}
