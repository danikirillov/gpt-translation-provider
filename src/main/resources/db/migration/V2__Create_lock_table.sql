-- Table for distributed locking of scheduled jobs
CREATE TABLE IF NOT EXISTS multi_instance_locks
(
    lock_name TEXT PRIMARY KEY,
    last_execution TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Insert initial record for cleanup
INSERT INTO multi_instance_locks (lock_name, last_execution)
VALUES ('event_cleanup', TIMESTAMP '2000-04-06 09:00:00+00');

-- Insert initial record for event resubmition
INSERT INTO multi_instance_locks (lock_name, last_execution)
VALUES ('event_resubmit', TIMESTAMP '2000-04-06 09:00:00+00');

