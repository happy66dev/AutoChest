package io.github.autochest.preference;

/**
 * 玩家背包槽位对整理和补货两项操作的权限状态。
 */
public enum InventorySlotMode {
    /** 允许整理，也允许补货。 */
    ALLOW_BOTH(true, true),
    /** 允许整理，但禁止补货。 */
    DEPOSIT_ONLY(true, false),
    /** 禁止整理，但允许补货。 */
    RESTOCK_ONLY(false, true),
    /** 整理和补货都禁止。 */
    DISABLED(false, false);

    /** 是否允许 deposit 使用该槽位。 */
    private final boolean depositAllowed;

    /** 是否允许 restock 使用该槽位。 */
    private final boolean restockAllowed;

    InventorySlotMode(boolean depositAllowed, boolean restockAllowed) {
        this.depositAllowed = depositAllowed;
        this.restockAllowed = restockAllowed;
    }

    /** 返回是否允许整理到箱子。 */
    public boolean allowsDeposit() {
        return depositAllowed;
    }

    /** 返回是否允许从箱子补货。 */
    public boolean allowsRestock() {
        return restockAllowed;
    }

    /** 返回 GUI 点击后的下一个状态。 */
    public InventorySlotMode next() {
        return switch (this) {
            case ALLOW_BOTH -> DEPOSIT_ONLY;
            case DEPOSIT_ONLY -> RESTOCK_ONLY;
            case RESTOCK_ONLY -> DISABLED;
            case DISABLED -> ALLOW_BOTH;
        };
    }

    /** 返回面向玩家的中文状态名称。 */
    public String displayName() {
        return switch (this) {
            case ALLOW_BOTH -> "允许整理和补货";
            case DEPOSIT_ONLY -> "仅整理";
            case RESTOCK_ONLY -> "仅补货";
            case DISABLED -> "不允许整理和补货";
        };
    }
}
