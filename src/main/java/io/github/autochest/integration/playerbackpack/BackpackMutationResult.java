package io.github.autochest.integration.playerbackpack;

public record BackpackMutationResult(Status status, long newRevision, int movedAmount,
                                     BackpackSnapshot snapshot, String diagnostic) {
    public BackpackMutationResult {
        // 喵~防御：状态、revision 和移动数量非法时拒绝不可信 provider 结果喵~
        if (status == null || newRevision < 0 || movedAmount < 0) {
            throw new IllegalArgumentException("PlayerBackpack mutation 结果非法喵~");
        }
    }

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
