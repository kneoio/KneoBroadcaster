package io.kneo.broadcaster.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Service to coordinate database transactions with file operations
 * Provides compensation patterns for file operations when database transactions fail
 */
@ApplicationScoped
public class TransactionCoordinatorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionCoordinatorService.class);

    private final PgPool pgPool;
    private final AtomicLong transactionIdGenerator = new AtomicLong(0);
    private final ConcurrentHashMap<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();

    @Inject
    public TransactionCoordinatorService(PgPool pgPool) {
        this.pgPool = pgPool;
    }

    public <T> Uni<T> executeCoordinatedTransaction(
            Function<CoordinatedTransactionContext, Uni<T>> operation) {

        String transactionId = generateTransactionId();
        TransactionContext context = new TransactionContext(transactionId);
        activeTransactions.put(transactionId, context);

        LOGGER.debug("Starting coordinated transaction: {}", transactionId);

        return pgPool.withTransaction(sqlConnection -> {
            CoordinatedTransactionContext coordContext = new CoordinatedTransactionContext(
                    transactionId, sqlConnection, context);

            return operation.apply(coordContext)
                    .onItem().invoke(result -> {
                        LOGGER.debug("Coordinated transaction completed successfully: {}", transactionId);
                        activeTransactions.remove(transactionId);
                    })
                    .onFailure().invoke(failure -> {
                        LOGGER.error("Coordinated transaction failed: {}, starting compensation", transactionId, failure);
                        // Transaction will auto-rollback, but we need to compensate file operations
                        compensateFileOperations(context)
                                .subscribe().with(
                                        ignored -> {
                                            LOGGER.info("Compensation completed for transaction: {}", transactionId);
                                            activeTransactions.remove(transactionId);
                                        },
                                        compensationError -> {
                                            LOGGER.error("Compensation failed for transaction: {}", transactionId, compensationError);
                                            activeTransactions.remove(transactionId);
                                        }
                                );
                    });
        });
    }

    public <T> Uni<T> executeFileOperation(
            CoordinatedTransactionContext context,
            Uni<T> fileOperation,
            Uni<Void> compensationAction) {

        return fileOperation
                .onItem().invoke(result -> {
                    // Register compensation action for rollback
                    context.addCompensationAction(compensationAction);
                    LOGGER.debug("File operation completed and compensation registered for transaction: {}",
                            context.getTransactionId());
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("File operation failed in transaction: {}", context.getTransactionId(), failure);
                });
    }

    public Uni<Void> executeAtomicFileOperations(
            CoordinatedTransactionContext context,
            List<FileOperation> operations) {

        List<Uni<Void>> operationUnis = new ArrayList<>();
        List<Uni<Void>> compensationActions = new ArrayList<>();

        for (FileOperation operation : operations) {
            operationUnis.add(operation.execute());
            compensationActions.add(operation.getCompensation());
        }

        return Uni.combine().all().unis(operationUnis)
                .discardItems()
                .onItem().invoke(() -> {
                    compensationActions.forEach(context::addCompensationAction);
                    LOGGER.debug("Atomic file operations completed for transaction: {}", context.getTransactionId());
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Atomic file operations failed in transaction: {}", context.getTransactionId(), failure);
                });
    }

    private Uni<Void> compensateFileOperations(TransactionContext context) {
        List<Uni<Void>> compensations = new ArrayList<>(context.getCompensationActions());

        if (compensations.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        LOGGER.info("Executing {} compensation actions for transaction: {}",
                compensations.size(), context.getTransactionId());

        // Execute compensations in reverse order (LIFO)
        List<Uni<Void>> reversedCompensations = new ArrayList<>(compensations);
        java.util.Collections.reverse(reversedCompensations);

        return Uni.combine().all().unis(reversedCompensations)
                .discardItems()
                .onFailure().invoke(failure ->
                        LOGGER.error("Some compensation actions failed for transaction: {}",
                                context.getTransactionId(), failure))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private String generateTransactionId() {
        return "TX-" + System.currentTimeMillis() + "-" + transactionIdGenerator.incrementAndGet();
    }

    public TransactionStats getStats() {
        return TransactionStats.builder()
                .activeTransactions(activeTransactions.size())
                .totalCompensationActions(activeTransactions.values().stream()
                        .mapToInt(ctx -> ctx.getCompensationActions().size())
                        .sum())
                .build();
    }

    // Context classes
    public static class CoordinatedTransactionContext {
        @Getter
        private final String transactionId;
        @Getter
        private final SqlConnection sqlConnection;
        private final TransactionContext context;

        public CoordinatedTransactionContext(String transactionId, SqlConnection sqlConnection,
                                             TransactionContext context) {
            this.transactionId = transactionId;
            this.sqlConnection = sqlConnection;
            this.context = context;
        }

        public void addCompensationAction(Uni<Void> action) {
            context.addCompensationAction(action);
        }
    }

    private static class TransactionContext {
        @Getter
        private final String transactionId;
        private final List<Uni<Void>> compensationActions = new ArrayList<>();

        public TransactionContext(String transactionId) {
            this.transactionId = transactionId;
        }

        public synchronized void addCompensationAction(Uni<Void> action) {
            compensationActions.add(action);
        }

        public synchronized List<Uni<Void>> getCompensationActions() {
            return new ArrayList<>(compensationActions);
        }
    }

    public interface FileOperation {
        Uni<Void> execute();
        Uni<Void> getCompensation();
    }

    @Getter
    public static class TransactionStats {
        private final int activeTransactions;
        private final int totalCompensationActions;

        private TransactionStats(int activeTransactions, int totalCompensationActions) {
            this.activeTransactions = activeTransactions;
            this.totalCompensationActions = totalCompensationActions;
        }

        public static TransactionStatsBuilder builder() {
            return new TransactionStatsBuilder();
        }

        public static class TransactionStatsBuilder {
            private int activeTransactions;
            private int totalCompensationActions;

            public TransactionStatsBuilder activeTransactions(int activeTransactions) {
                this.activeTransactions = activeTransactions;
                return this;
            }

            public TransactionStatsBuilder totalCompensationActions(int totalCompensationActions) {
                this.totalCompensationActions = totalCompensationActions;
                return this;
            }

            public TransactionStats build() {
                return new TransactionStats(activeTransactions, totalCompensationActions);
            }
        }
    }
}