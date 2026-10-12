package com.loopers.application.brand;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.BrandService;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.show-sql=false")
class BrandHintComparisonTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private BrandService brands;
    @Autowired private BrandFacade facade;
    @Autowired private ProductFacade products;
    @Autowired private EntityManager em;
    @Autowired private DatabaseCleanUp cleanUp;

    enum Strategy { ID_BY_ID, RR_PLAIN, RR_HINT, RC_PLAIN }

    @AfterEach
    void clean() {
        cleanUp.truncateAllTables();
    }

    static Stream<Arguments> cases() {
        return Stream.of(false, true).flatMap(analyzed -> Stream.of(
            Arguments.of(2, 100, analyzed), Arguments.of(100, 100, analyzed),
            Arguments.of(2, 1000, analyzed)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void comparesPlansLocksAndCommittedDeletionTime(int brandCount, int perBrand, boolean analyzed) {
        for (Strategy strategy : List.of(Strategy.RR_PLAIN, Strategy.RR_HINT, Strategy.RC_PLAIN)) {
            cleanUp.truncateAllTables();
            prepare(brandCount, perBrand, true);
            if (analyzed) {
                jdbc.queryForList("analyze table product");
            }
            var plan = jdbc.queryForList("explain " + sql(strategy), 1L);
            if (strategy == Strategy.RR_HINT) {
                assertThat(plan).singleElement().satisfies(row ->
                    assertThat(row.get("key")).isEqualTo("idx_product_brand_deleted"));
            }
            long target = jdbc.queryForObject("select min(id) from product where brand_id=1", Long.class);
            long other = jdbc.queryForObject("select min(id) from product where brand_id=2", Long.class);
            boolean[] blocked = new boolean[1];
            transaction(strategy).executeWithoutResult(status -> {
                delete(strategy);
                assertThat(lockUnavailable(target)).isTrue();
                blocked[0] = lockUnavailable(other);
            });
            if (strategy != Strategy.RR_PLAIN) {
                assertThat(blocked[0]).isFalse();
            }
            List<Double> times = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                restore();
                long start = System.nanoTime();
                transaction(strategy).executeWithoutResult(status -> delete(strategy));
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                if (round > 0) {
                    times.add(ms);
                }
                assertThat(jdbc.queryForObject(
                    "select count(*) from product where brand_id=1 and deleted_at is null", Integer.class)).isZero();
                assertThat(jdbc.queryForObject(
                    "select count(*) from product where brand_id<>1 and deleted_at is null", Integer.class))
                    .isEqualTo((brandCount - 1) * perBrand);
            }
            times.sort(Double::compareTo);
            System.out.printf("HINT_COMPARE brands=%d perBrand=%d analyzed=%s strategy=%s "
                + "key=%s type=%s otherBlocked=%s medianMs=%.3f samples=%s%n",
                brandCount, perBrand, analyzed, strategy, plan.getFirst().get("key"),
                plan.getFirst().get("type"), blocked[0], times.get(1), times);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observesInsertsIntoAnEmptyOtherBrand(boolean oldDeletedBoundary) throws Exception {
        for (Strategy strategy : Strategy.values()) {
            cleanUp.truncateAllTables();
            prepare(2, 0, oldDeletedBoundary);
            jdbc.update("insert into product(brand_id,name,price,quantity,created_at,updated_at) "
                + "values (1,'target',1000,1,'2020-01-01','2020-01-01')");
            var executor = Executors.newSingleThreadExecutor();
            var pending = new ArrayList<java.util.concurrent.Future<Long>>();
            boolean[] blocked = new boolean[1];
            try {
                transaction(strategy).executeWithoutResult(status -> {
                    delete(strategy);
                    snapshotLocks(strategy, oldDeletedBoundary);
                    pending.add(executor.submit(() -> products.create(2L, "new other product", 1000, 1).id()));
                    try {
                        pending.getFirst().get(300, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException expected) {
                        assertInsertWait();
                        blocked[0] = true;
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                long inserted = pending.getFirst().get(10, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("select deleted_at from product where id=?", Object.class, inserted))
                    .isNull();
                boolean expectedWait = !oldDeletedBoundary
                    && (strategy == Strategy.RR_PLAIN || strategy == Strategy.RR_HINT);
                assertThat(blocked[0]).as("strategy=%s, oldDeletedBoundary=%s", strategy, oldDeletedBoundary)
                    .isEqualTo(expectedWait);
                assertThat(jdbc.queryForObject("select count(*) from product where brand_id=1 and deleted_at is null",
                    Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select brand_id from product where id=?", Long.class, inserted))
                    .isEqualTo(2L);
                if (oldDeletedBoundary) {
                    assertThat(jdbc.queryForObject("select deleted_at from product where name='old'",
                        java.sql.Timestamp.class)).isEqualTo(java.sql.Timestamp.valueOf("2020-01-02 00:00:00"));
                }
                System.out.printf("HINT_INSERT strategy=%s oldDeletedBoundary=%s blocked=%s%n",
                    strategy, oldDeletedBoundary, blocked[0]);
            } finally {
                pending.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private void prepare(int brandCount, int perBrand, boolean oldDeleted) {
        List<Object[]> brandRows = new ArrayList<>();
        List<Object[]> productRows = new ArrayList<>();
        for (int brand = 1; brand <= brandCount; brand++) {
            brandRows.add(new Object[] {brand, "brand " + brand});
        }
        jdbc.batchUpdate("insert into brand(id,name,created_at,updated_at) values (?,?,'2020-01-01','2020-01-01')",
            brandRows);
        for (int i = 0; i < perBrand; i++) {
            for (int brand = 1; brand <= brandCount; brand++) {
                productRows.add(new Object[] {brand});
            }
        }
        jdbc.batchUpdate("insert into product(brand_id,name,price,quantity,created_at,updated_at) "
            + "values (?,'product',1000,5,'2020-01-01','2020-01-01')", productRows);
        if (oldDeleted) {
            jdbc.update("insert into product(brand_id,name,price,quantity,created_at,updated_at,deleted_at) "
                + "values (1,'old',1000,0,'2020-01-01','2020-01-01','2020-01-02')");
        }
    }

    private String sql(Strategy strategy) {
        String hint = strategy == Strategy.RR_HINT ? " FORCE INDEX (idx_product_brand_deleted)" : "";
        return "update product" + hint + " set deleted_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) "
            + "where brand_id=? and deleted_at is null";
    }

    private TransactionTemplate transaction(Strategy strategy) {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(strategy == Strategy.RC_PLAIN
            ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(20);
        return transaction;
    }

    private void delete(Strategy strategy) {
        assertThat(jdbc.queryForObject("select @@session.transaction_isolation", String.class))
            .isEqualTo(strategy == Strategy.RC_PLAIN ? "READ-COMMITTED" : "REPEATABLE-READ");
        if (strategy == Strategy.ID_BY_ID) {
            facade.delete(1L);
            em.flush();
            em.clear();
            return;
        }
        brands.delete(1L);
        em.flush();
        jdbc.update(sql(strategy), 1L);
        em.clear();
    }

    private void restore() {
        jdbc.update("update brand set deleted_at=null where id=1");
        jdbc.update("update product set deleted_at=null where brand_id=1 and name<>'old'");
    }

    private boolean lockUnavailable(long id) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("select id from product where id=? for update nowait")) {
                query.setLong(1, id);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return false;
                }
            } catch (SQLException exception) {
                if (exception.getErrorCode() == 3572) {
                    return true;
                }
                throw exception;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertInsertWait() {
        try (var app = dataSource.getConnection();
             var root = DriverManager.getConnection(app.getMetaData().getURL(), "root", "test");
             var query = root.createStatement();
             var rows = query.executeQuery("select l.index_name, l.lock_mode, l.lock_data, "
                 + "b.lock_mode as blocking_mode, b.lock_data as blocking_data from performance_schema.data_lock_waits w "
                 + "join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id "
                 + "join performance_schema.data_locks b on b.engine_lock_id=w.blocking_engine_lock_id "
                 + "where l.object_schema=database() and l.object_name='product'")) {
            assertThat(rows.next()).isTrue();
            System.out.printf("HINT_INSERT_LOCK index=%s requestingMode=%s requestingData=%s blockingMode=%s "
                + "blockingData=%s%n", rows.getString(1), rows.getString(2), rows.getString(3),
                rows.getString(4), rows.getString(5));
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void snapshotLocks(Strategy strategy, boolean oldDeletedBoundary) {
        try (var app = dataSource.getConnection();
             var root = DriverManager.getConnection(app.getMetaData().getURL(), "root", "test");
             var query = root.createStatement();
             var rows = query.executeQuery("select index_name,lock_mode,lock_data from performance_schema.data_locks "
                 + "where object_schema=database() and object_name='product' and lock_type='RECORD' "
                 + "order by index_name,lock_data,lock_mode")) {
            List<String> locks = new ArrayList<>();
            while (rows.next()) {
                locks.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3));
            }
            System.out.printf("HINT_SNAPSHOT strategy=%s oldDeletedBoundary=%s locks=%s%n",
                strategy, oldDeletedBoundary, locks);
            if (strategy == Strategy.RR_HINT) {
                if (oldDeletedBoundary) {
                    assertThat(locks).anyMatch(lock -> lock.startsWith("idx_product_brand_deleted:X,GAP:"));
                    assertThat(locks).noneMatch(lock -> lock.contains("supremum pseudo-record"));
                } else {
                    assertThat(locks).anyMatch(lock -> lock.contains("supremum pseudo-record"));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
