package co.sheet.gpttranslationprovider.event_management;

import static co.sheet.gpttranslationprovider.event_management.CleanupService.LOCK_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class CleanupServiceTest {

    @Autowired
    CleanupService cleanupService;

    @Autowired
    MultiInstanceLockRepository lockRepository;

    @MockitoBean
    CompletedEventPublications completedEventPublications;

    @BeforeEach
    void setUp() {
        // Reset the lock to a very old timestamp using the proper update method
        // The lock is pre-populated by Flyway migration V2__Create_lock_table.sql
        var veryOldTimestamp = Instant.parse("2000-04-06T09:00:00Z");
        lockRepository.updateLastExecution(LOCK_NAME, veryOldTimestamp);
    }

    @Test
    void cleanupOldEvents_shouldExecuteCleanup_whenLastExecutionWasVeryOld() {
        // Act
        cleanupService.cleanupOldEvents();

        // Assert
        verify(completedEventPublications, times(1))
            .deletePublicationsOlderThan(Duration.ofDays(1));

        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void cleanupOldEvents_shouldExecuteCleanup_whenLastExecutionWasMoreThan2359HoursAgo() {
        // Arrange
        var oldExecution = Instant.now().minus(Duration.ofHours(23).plusMinutes(59));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        // Act
        cleanupService.cleanupOldEvents();

        // Assert
        verify(completedEventPublications, times(1))
            .deletePublicationsOlderThan(Duration.ofDays(1));

        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(oldExecution);
        assertThat(lock.get().lastExecution()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void cleanupOldEvents_shouldSkipCleanup_whenExecutedRecently() {
        // Arrange
        var recentExecution = Instant.now().minus(Duration.ofHours(1));
        lockRepository.updateLastExecution(LOCK_NAME, recentExecution);

        // Act
        cleanupService.cleanupOldEvents();

        // Assert
        verify(completedEventPublications, never())
            .deletePublicationsOlderThan(any(Duration.class));

        // Verify lock was NOT updated
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution().getEpochSecond()).isEqualTo(recentExecution.getEpochSecond());
    }

    @Test
    void cleanupOldEvents_shouldHandleExceptionGracefully_andStillUpdateLock() {
        // Arrange
        var oldExecution = Instant.now().minus(Duration.ofHours(25));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        doThrow(new RuntimeException("Database connection failed"))
            .when(completedEventPublications).deletePublicationsOlderThan(any(Duration.class));

        // Act
        cleanupService.cleanupOldEvents();

        // Assert
        verify(completedEventPublications, times(1))
            .deletePublicationsOlderThan(Duration.ofDays(1));

        // Verify lock was still updated despite the exception
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(oldExecution);
    }

    @Test
    void cleanupOldEvents_shouldPreventConcurrentExecutionAcrossMultipleInstances() throws InterruptedException {
        // Arrange - Set old execution time so cleanup will run
        var oldExecution = Instant.now().minus(Duration.ofHours(25));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        // Simulate multiple instances trying to execute cleanup concurrently
        int numberOfInstances = 5;
        var startLatch = new CountDownLatch(1);  // To start all threads at once
        var finishLatch = new CountDownLatch(numberOfInstances);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Act - Simulate 5 instances all trying to run cleanup at exactly the same time
            for (int i = 0; i < numberOfInstances; i++) {
                executor.submit(() -> {
                    try {
                        // Wait for all threads to be ready, then start together
                        startLatch.await();

                        // Each thread attempts cleanup
                        cleanupService.cleanupOldEvents();
                    } catch (Exception e) {
                        // Ignore exceptions in test threads
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            // Start all threads at once
            startLatch.countDown();

            // Wait for all threads to complete
            assertThat(finishLatch.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // Assert - Due to FOR UPDATE lock, the executions are serialized:
        // 1. First thread acquires lock, sees old timestamp, executes cleanup, updates lock
        // 2. Remaining threads acquire lock sequentially, see recent timestamp, skip cleanup
        // Result: deletePublicationsOlderThan should be called EXACTLY once
        verify(completedEventPublications, times(1))
            .deletePublicationsOlderThan(Duration.ofDays(1));

        // Verify the lock timestamp was updated
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution())
            .as("Lock timestamp should be updated by the first instance that acquired the lock")
            .isAfter(oldExecution)
            .isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }
}

