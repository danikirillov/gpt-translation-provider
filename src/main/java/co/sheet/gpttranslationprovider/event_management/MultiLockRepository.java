package co.sheet.gpttranslationprovider.event_management;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
interface MultiInstanceLockRepository extends CrudRepository<MultiInstanceLock, String> {

    @Transactional
    @Query("""
        SELECT last_execution
        FROM multi_instance_locks
        WHERE lock_name = :lockName
        FOR UPDATE
        """)
    Instant findLastExecutionWithLock(String lockName);

    @Modifying
    @Transactional
    @Query("UPDATE multi_instance_locks SET last_execution = :lastExecution WHERE lock_name = :lockName")
    void updateLastExecution(String lockName, Instant lastExecution);
}

@Table("multi_instance_locks")
record MultiInstanceLock(
    @Id String lockName,
    Instant lastExecution
) {

}
