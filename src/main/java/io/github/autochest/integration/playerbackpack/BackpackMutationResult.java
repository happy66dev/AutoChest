package io.github.autochest.integration.playerbackpack;

public record BackpackMutationResult(Status status, long newRevision, int movedAmount,
                                     BackpackSnapshot snapshot, String diagnostic) {
    public enum Status {
        APPLIED,
        SERVICE_UNAVAILABLE,
        RECONCILIATION_REQUIRED,
        FAILED
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }
}
