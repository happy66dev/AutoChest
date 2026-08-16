package io.github.autochest.integration.playerbackpack;

public enum BackpackOperationFailure {
    NONE,
    SERVICE_UNAVAILABLE,
    TARGET_BUSY,
    OPERATION_INVALID,
    REVISION_CONFLICT,
    PRECONDITION_FAILED,
    STORAGE_FAILURE,
    RECONCILIATION_REQUIRED
}
