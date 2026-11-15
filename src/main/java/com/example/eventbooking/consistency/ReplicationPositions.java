package com.example.eventbooking.consistency;

/** Reads WAL positions (LSNs) from the primary and the replica. */
public interface ReplicationPositions {

    /** The primary's current WAL position, e.g. {@code 0/16B3748}. Call after the write has committed. */
    String primaryPosition();

    /** True if the replica has replayed WAL up to and including {@code lsn}. */
    boolean replicaHasReached(String lsn);
}
