package co.edu.corhuila.barbersaas.loyalty.adapter.out.persistence;

import co.edu.corhuila.barbersaas.loyalty.application.port.in.Page;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.Idempotency.KeyTaken;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.LoyaltyRepository;
import co.edu.corhuila.barbersaas.loyalty.application.port.out.OutboxEvent;
import co.edu.corhuila.barbersaas.loyalty.domain.model.CouponStatus;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyCard;
import co.edu.corhuila.barbersaas.loyalty.domain.model.LoyaltyTransaction;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardCoupon;
import co.edu.corhuila.barbersaas.loyalty.domain.model.RewardsConfig;
import co.edu.corhuila.barbersaas.loyalty.domain.model.TransactionType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The loyalty schema (06-data/models.md §6 and §10) as loyalty_app. Every read filters by the
 * barbershop; a transaction reaches it through its card. Counts change by deltas in the statement that
 * checks them, so chk_loyalty_card_counts and uq_loyalty_transaction_sticker_per_appointment are the
 * final guarantees against concurrent requests.
 */
public class JdbcLoyaltyRepository implements LoyaltyRepository {

    private static final String CONFIG = "id, barbershop_id, stickers_required, reward_description, is_active";
    private static final String CARD = "id, barbershop_id, client_id, stickers_count, total_rewards_redeemed, last_updated";
    private static final String TX = "t.id, t.loyalty_card_id, t.appointment_id, t.type, t.granted_by_user_id, t.created_at";
    private static final String TX_OF_TENANT = "FROM loyalty.loyalty_transaction t "
            + "JOIN loyalty.loyalty_card c ON c.id = t.loyalty_card_id WHERE c.barbershop_id = ?";
    private static final String COUPON = "id, barbershop_id, client_id, status, appointment_id, created_at, used_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcLoyaltyRepository(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public Optional<RewardsConfig> config(UUID tenant) {
        return jdbc.query("SELECT " + CONFIG + " FROM loyalty.loyalty_rewards_config WHERE barbershop_id = ?",
                (rs, n) -> new RewardsConfig(rs.getObject("id", UUID.class), rs.getObject("barbershop_id", UUID.class),
                        rs.getInt("stickers_required"), rs.getString("reward_description"), rs.getBoolean("is_active")),
                tenant).stream().findFirst();
    }

    /** One per barbershop (uq_loyalty_rewards_config_barbershop): created or replaced in one statement. */
    @Override
    public void saveConfig(RewardsConfig c) {
        jdbc.update("INSERT INTO loyalty.loyalty_rewards_config (" + CONFIG + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (barbershop_id) DO UPDATE SET stickers_required = EXCLUDED.stickers_required, "
                        + "reward_description = EXCLUDED.reward_description, is_active = EXCLUDED.is_active",
                c.id(), c.barbershopId(), c.stickersRequired(), c.rewardDescription(), c.active());
    }

    @Override
    public Optional<LoyaltyCard> card(UUID tenant, UUID cardId) {
        return jdbc.query("SELECT " + CARD + " FROM loyalty.loyalty_card WHERE barbershop_id = ? AND id = ?",
                (rs, n) -> card(rs), tenant, cardId).stream().findFirst();
    }

    @Override
    public Optional<LoyaltyCard> cardOf(UUID tenant, UUID clientId) {
        return jdbc.query("SELECT " + CARD + " FROM loyalty.loyalty_card WHERE barbershop_id = ? AND client_id = ?",
                (rs, n) -> card(rs), tenant, clientId).stream().findFirst();
    }

    @Override
    public Page<LoyaltyCard> cards(UUID tenant, UUID clientId, Boolean canRedeem, Integer required, Page.Request page) {
        StringBuilder from = new StringBuilder("FROM loyalty.loyalty_card WHERE barbershop_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenant));
        if (clientId != null) {
            from.append(" AND client_id = ?");
            args.add(clientId);
        }
        if (canRedeem != null && required == null) {
            from.append(canRedeem ? " AND FALSE" : "");                // no active rule: nobody can redeem
        } else if (canRedeem != null) {
            from.append(canRedeem ? " AND stickers_count >= ?" : " AND stickers_count < ?");
            args.add(required);
        }
        return JdbcPages.page(jdbc, CARD, new JdbcPages.Query(from.toString(), args, "ORDER BY last_updated DESC, id"),
                (rs, n) -> card(rs), page);
    }

    @Override
    public Page<LoyaltyTransaction> transactions(UUID cardId, TransactionType type, Page.Request page) {
        StringBuilder from = new StringBuilder("FROM loyalty.loyalty_transaction t WHERE t.loyalty_card_id = ?");
        List<Object> args = new ArrayList<>(List.of(cardId));
        if (type != null) {
            from.append(" AND t.type = ?");
            args.add(type.name());
        }
        return JdbcPages.page(jdbc, TX, new JdbcPages.Query(from.toString(), args, "ORDER BY t.created_at DESC, t.id"),
                (rs, n) -> transaction(rs), page);
    }

    @Override
    public Optional<LoyaltyTransaction> transaction(UUID tenant, UUID transactionId) {
        return jdbc.query("SELECT " + TX + " " + TX_OF_TENANT + " AND t.id = ?", (rs, n) -> transaction(rs),
                tenant, transactionId).stream().findFirst();
    }

    @Override
    public Optional<LoyaltyTransaction> redemptionOf(RewardCoupon coupon) {
        return jdbc.query("SELECT " + TX + " " + TX_OF_TENANT + " AND c.client_id = ? AND t.type = 'REWARD_REDEEMED' "
                        + "AND t.created_at = ?", (rs, n) -> transaction(rs),
                coupon.barbershopId(), coupon.clientId(), Timestamp.from(coupon.createdAt())).stream().findFirst();
    }

    @Override
    public Optional<RewardCoupon> coupon(UUID tenant, UUID couponId) {
        return jdbc.query("SELECT " + COUPON + " FROM loyalty.reward_coupon WHERE barbershop_id = ? AND id = ?",
                (rs, n) -> coupon(rs), tenant, couponId).stream().findFirst();
    }

    @Override
    public Page<RewardCoupon> coupons(UUID tenant, UUID clientId, CouponStatus status, Page.Request page) {
        StringBuilder from = new StringBuilder("FROM loyalty.reward_coupon WHERE barbershop_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenant));
        if (clientId != null) {
            from.append(" AND client_id = ?");
            args.add(clientId);
        }
        if (status != null) {
            from.append(" AND status = ?");
            args.add(status.name());
        }
        return JdbcPages.page(jdbc, COUPON, new JdbcPages.Query(from.toString(), args, "ORDER BY created_at DESC, id"),
                (rs, n) -> coupon(rs), page);
    }

    @Override
    public Optional<Idempotency.Stored> findKey(String key, String operation) {
        return JdbcIdempotency.find(jdbc, key, operation);
    }

    @Override
    public LoyaltyCard saveSticker(LoyaltyCard card, boolean newCard, LoyaltyTransaction t, Idempotency.Key key,
                                   OutboxEvent event) {
        write(() -> {
            if (newCard) {
                jdbc.update("INSERT INTO loyalty.loyalty_card (" + CARD + ") VALUES (?, ?, ?, ?, ?, ?)", card.id(),
                        card.barbershopId(), card.clientId(), card.stickersCount(), card.totalRewardsRedeemed(),
                        Timestamp.from(card.lastUpdated()));
            } else {
                jdbc.update("UPDATE loyalty.loyalty_card SET stickers_count = stickers_count + 1, last_updated = ? "
                        + "WHERE barbershop_id = ? AND id = ?", Timestamp.from(t.createdAt()), card.barbershopId(), card.id());
            }
            insert(t);
            if (key != null) {
                JdbcIdempotency.insert(jdbc, key, t.id());
            }
            insert(event);
        });
        return card(card.barbershopId(), card.id()).orElseThrow();
    }

    @Override
    public LoyaltyCard saveRedemption(LoyaltyCard card, int required, LoyaltyTransaction t, RewardCoupon coupon,
                                      Idempotency.Key key, OutboxEvent event) {
        write(() -> {
            jdbc.update("UPDATE loyalty.loyalty_card SET stickers_count = stickers_count - ?, "
                            + "total_rewards_redeemed = total_rewards_redeemed + 1, last_updated = ? "
                            + "WHERE barbershop_id = ? AND id = ?",
                    required, Timestamp.from(t.createdAt()), card.barbershopId(), card.id());
            insert(t);
            jdbc.update("INSERT INTO loyalty.reward_coupon (" + COUPON + ") VALUES (?, ?, ?, ?, ?, ?, ?)", coupon.id(),
                    coupon.barbershopId(), coupon.clientId(), coupon.status().name(), coupon.appointmentId(),
                    Timestamp.from(coupon.createdAt()), null);
            JdbcIdempotency.insert(jdbc, key, coupon.id());
            insert(event);
        });
        return card(card.barbershopId(), card.id()).orElseThrow();
    }

    @Override
    public void saveCouponUse(RewardCoupon c) {
        int changed = jdbc.update("UPDATE loyalty.reward_coupon SET status = 'USED', appointment_id = ?, used_at = ? "
                + "WHERE barbershop_id = ? AND id = ? AND status = 'ACTIVE'", c.appointmentId(), Timestamp.from(c.usedAt()),
                c.barbershopId(), c.id());
        if (changed != 1) {
            throw new CouponTaken();
        }
    }

    /** One transaction; the constraint and key violations become what the use case answers. */
    private void write(Runnable statements) {
        try {
            tx.executeWithoutResult(status -> statements.run());
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("uq_loyalty_transaction_sticker_per_appointment")) {
                throw new StickerAlreadyGranted();
            }
            if (message.contains("chk_loyalty_card_counts")) {
                throw new NotEnoughStickers();
            }
            if (message.contains("uq_loyalty_card_client_barbershop")) {
                throw new CardTaken();
            }
            if (message.contains("pk_idempotency_key")) {
                throw new KeyTaken();
            }
            throw e;
        }
    }

    private void insert(LoyaltyTransaction t) {
        jdbc.update("INSERT INTO loyalty.loyalty_transaction (id, loyalty_card_id, appointment_id, type, "
                        + "granted_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, ?)", t.id(), t.cardId(), t.appointmentId(),
                t.type().name(), t.grantedByUserId(), Timestamp.from(t.createdAt()));
    }

    /** Inside the caller's transaction. The correlation id ties the event to the request that caused it. */
    private void insert(OutboxEvent e) {
        String correlationId = Optional.ofNullable(MDC.get("correlationId")).orElse("none");
        try {
            jdbc.update("INSERT INTO loyalty.outbox_event (id, aggregate_type, aggregate_id, event_type, payload, "
                            + "correlation_id, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)", e.id(),
                    OutboxEvent.AGGREGATE_TYPE, e.aggregateId(), e.type(), json.writeValueAsString(e.payload()),
                    correlationId, Timestamp.from(e.occurredAt()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The payload of " + e.type() + " cannot be written as JSON", ex);
        }
    }

    private static LoyaltyCard card(ResultSet rs) throws SQLException {
        return LoyaltyCard.restore(rs.getObject("id", UUID.class), rs.getObject("barbershop_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getInt("stickers_count"), rs.getInt("total_rewards_redeemed"),
                rs.getTimestamp("last_updated").toInstant());
    }

    private static LoyaltyTransaction transaction(ResultSet rs) throws SQLException {
        return new LoyaltyTransaction(rs.getObject("id", UUID.class), rs.getObject("loyalty_card_id", UUID.class),
                rs.getObject("appointment_id", UUID.class), TransactionType.valueOf(rs.getString("type")),
                rs.getObject("granted_by_user_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private static RewardCoupon coupon(ResultSet rs) throws SQLException {
        Timestamp usedAt = rs.getTimestamp("used_at");
        return RewardCoupon.restore(rs.getObject("id", UUID.class), rs.getObject("barbershop_id", UUID.class),
                rs.getObject("client_id", UUID.class), CouponStatus.valueOf(rs.getString("status")),
                rs.getObject("appointment_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                usedAt == null ? null : usedAt.toInstant());
    }
}
