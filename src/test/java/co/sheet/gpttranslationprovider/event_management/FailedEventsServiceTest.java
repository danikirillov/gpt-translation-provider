package co.sheet.gpttranslationprovider.event_management;

import static co.sheet.gpttranslationprovider.event_management.FailedEventsService.LOCK_NAME;
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
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class FailedEventsServiceTest {

    @Autowired
    FailedEventsService failedEventsService;

    @Autowired
    MultiInstanceLockRepository lockRepository;

    @MockitoBean(name = "incompleteEvents")
    IncompleteEventPublications incompleteEventPublications;

    @BeforeEach
    void setUp() {
        // Reset the lock to a very old timestamp using the proper update method
        // The lock is pre-populated by Flyway migration V2__Create_lock_table.sql
        var veryOldTimestamp = Instant.parse("2000-04-06T09:00:00Z");
        lockRepository.updateLastExecution(LOCK_NAME, veryOldTimestamp);
    }

    @Test
    void resubmitFailedEvents_shouldExecuteResubmission_whenLastExecutionWasVeryOld() {
        // Act
        failedEventsService.resubmitFailedEvents();

        // Assert
        verify(incompleteEventPublications, times(1))
            .resubmitIncompletePublicationsOlderThan(Duration.ofHours(1));

        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void resubmitFailedEvents_shouldExecuteResubmission_whenLastExecutionWasMoreThan23HoursAgo() {
        // Arrange
        var oldExecution = Instant.now().minus(Duration.ofHours(23).plusMinutes(59));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        // Act
        failedEventsService.resubmitFailedEvents();

        // Assert
        verify(incompleteEventPublications, times(1))
            .resubmitIncompletePublicationsOlderThan(Duration.ofHours(1));

        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(oldExecution);
        assertThat(lock.get().lastExecution()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void resubmitFailedEvents_shouldSkipResubmission_whenExecutedRecently() {
        // Arrange
        var recentExecution = Instant.now().minus(Duration.ofHours(1));
        lockRepository.updateLastExecution(LOCK_NAME, recentExecution);

        // Act
        failedEventsService.resubmitFailedEvents();

        // Assert
        verify(incompleteEventPublications, never())
            .resubmitIncompletePublicationsOlderThan(any(Duration.class));

        // Verify lock was NOT updated
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution().getEpochSecond()).isEqualTo(recentExecution.getEpochSecond());
    }

    @Test
    void resubmitFailedEvents_shouldHandleExceptionGracefully_andStillUpdateLock() {
        // Arrange
        var oldExecution = Instant.now().minus(Duration.ofHours(25));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        doThrow(new RuntimeException("Database connection failed"))
            .when(incompleteEventPublications).resubmitIncompletePublicationsOlderThan(any(Duration.class));

        // Act
        failedEventsService.resubmitFailedEvents();

        // Assert
        verify(incompleteEventPublications, times(1))
            .resubmitIncompletePublicationsOlderThan(Duration.ofHours(1));

        // Verify lock was still updated despite the exception
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution()).isAfter(oldExecution);
    }

    @Test
    void resubmitFailedEvents_shouldPreventConcurrentExecutionAcrossMultipleInstances() throws InterruptedException {
        // Arrange - Set old execution time so resubmission will run
        var oldExecution = Instant.now().minus(Duration.ofHours(25));
        lockRepository.updateLastExecution(LOCK_NAME, oldExecution);

        // Simulate multiple instances trying to execute resubmission concurrently
        int numberOfInstances = 5;
        var startLatch = new CountDownLatch(1);  // To start all threads at once
        var finishLatch = new CountDownLatch(numberOfInstances);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Act - Simulate 5 instances all trying to run resubmission at exactly the same time
            for (int i = 0; i < numberOfInstances; i++) {
                executor.submit(() -> {
                    try {
                        // Wait for all threads to be ready, then start together
                        startLatch.await();

                        // Each thread attempts resubmission
                        failedEventsService.resubmitFailedEvents();
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
        // 1. First thread acquires lock, sees old timestamp, executes resubmission, updates lock
        // 2. Remaining threads acquire lock sequentially, see recent timestamp, skip resubmission
        // Result: resubmitIncompletePublicationsOlderThan should be called EXACTLY once
        verify(incompleteEventPublications, times(1))
            .resubmitIncompletePublicationsOlderThan(Duration.ofHours(1));

        // Verify the lock timestamp was updated
        var lock = lockRepository.findById(LOCK_NAME);
        assertThat(lock).isPresent();
        assertThat(lock.get().lastExecution())
            .as("Lock timestamp should be updated by the first instance that acquired the lock")
            .isAfter(oldExecution)
            .isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }
}

