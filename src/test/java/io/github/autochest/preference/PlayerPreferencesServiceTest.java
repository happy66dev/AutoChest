package io.github.autochest.preference;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.task.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家 JSON 偏好服务测试。
 * 验证两项操作独立保存并可由新服务实例恢复。
 */
class PlayerPreferencesServiceTest {

    /**
     * deposit 与 restock 的黑名单和排序模式必须独立持久化。
     *
     * @param temporaryDirectory JUnit 提供的独立临时目录。
     */
    @Test
    void preferences_jsonRoundTripKeepsDepositAndRestockIndependent(@TempDir Path temporaryDirectory) {
        // 创建用于首次写入的偏好服务。
        PlayerPreferencesService savingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        // 创建稳定玩家 UUID。
        UUID playerUuid = UUID.randomUUID();
        // 为 deposit 设置容器优先模式。
        assertTrue(savingService.setOrderMode(playerUuid, OperationType.DEPOSIT,
                ContainerOrderMode.CONTAINER_PRIORITY));
        // 仅向 deposit 黑名单加入木桶。
        assertTrue(savingService.setBlacklisted(playerUuid, OperationType.DEPOSIT,
                ContainerIdentity.ContainerType.BARREL, true));
        // 仅向 restock 黑名单加入末影箱。
        assertTrue(savingService.setBlacklisted(playerUuid, OperationType.RESTOCK,
                ContainerIdentity.ContainerType.ENDER_CHEST, true));
        // 锁定 deposit 主背包中的一个有物品或空槽均可用的槽位。
        assertTrue(savingService.setLockedInventorySlot(playerUuid, 9, true));
        // 锁定另一个主背包槽位。
        assertTrue(savingService.setLockedInventorySlot(playerUuid, 35, true));
        // 等待所有 JSON 写入任务完成。
        savingService.flushAndClose(5L);

        // 创建新服务实例模拟服务器重启后的重新加载。
        PlayerPreferencesService loadingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        // 分别读取两项操作快照。
        OperationPreferencesSnapshot depositSnapshot = loadingService.snapshot(playerUuid, OperationType.DEPOSIT);
        OperationPreferencesSnapshot restockSnapshot = loadingService.snapshot(playerUuid, OperationType.RESTOCK);

        // 验证 deposit 的模式和黑名单被准确恢复。
        assertEquals(ContainerOrderMode.CONTAINER_PRIORITY, depositSnapshot.getOrderMode());
        assertFalse(depositSnapshot.allows(ContainerIdentity.ContainerType.BARREL));
        // 验证 restock 没有继承 deposit 的排序模式或黑名单。
        assertEquals(ContainerOrderMode.DISTANCE, restockSnapshot.getOrderMode());
        assertTrue(restockSnapshot.allows(ContainerIdentity.ContainerType.BARREL));
        // 验证 restock 不继承 deposit 的容器黑名单，但共享槽位权限仍然生效。
        assertFalse(restockSnapshot.allows(ContainerIdentity.ContainerType.ENDER_CHEST));
        // 验证旧锁定槽位迁移为仅补货权限。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, depositSnapshot.getInventorySlotMode(9));
        assertEquals(InventorySlotMode.RESTOCK_ONLY, restockSnapshot.getInventorySlotMode(9));
        assertTrue(restockSnapshot.allowsRestock(9));
        loadingService.flushAndClose(5L);
    }

    /**
     * 手工 JSON 中的小数、溢出值和非 deposit 锁定字段不得误锁定主背包槽位。
     *
     * @param temporaryDirectory JUnit 提供的独立临时目录。
     * @throws Exception 玩家 JSON 写入失败时由测试框架报告。
     */
    @Test
    void preferences_jsonIgnoresInvalidLockedInventorySlots(@TempDir Path temporaryDirectory) throws Exception {
        // 创建稳定玩家 UUID。
        UUID playerUuid = UUID.randomUUID();
        // 构建该玩家的既有 JSON 文件路径。
        Path playerFile = temporaryDirectory.resolve("data").resolve("players").resolve(playerUuid + ".json");
        // 创建目标目录以模拟服务器此前已写入的玩家配置。
        Files.createDirectories(playerFile.getParent());
        // 写入包含小数、溢出值、字符串、布尔值和合法槽位的手工 JSON。
        Files.writeString(playerFile, """
                {"version":1,"deposit":{"lockedInventorySlots":[9.5,9,35,36,2147483648,"10",true]},
                "restock":{"lockedInventorySlots":[10]}}
                """);
        // 创建新服务以触发 JSON 容错加载。
        PlayerPreferencesService loadingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        // 读取 deposit 快照，验证仅精确合法整数槽位被保留。
        OperationPreferencesSnapshot depositSnapshot = loadingService.snapshot(playerUuid, OperationType.DEPOSIT);
        // 读取 restock 快照，验证共享权限对其同样生效。
        OperationPreferencesSnapshot restockSnapshot = loadingService.snapshot(playerUuid, OperationType.RESTOCK);

        // 验证小数 9.5 没有被截断为槽位 9，而整数 9 和 35 迁移为仅补货。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, depositSnapshot.getInventorySlotMode(9));
        assertEquals(InventorySlotMode.RESTOCK_ONLY, depositSnapshot.getInventorySlotMode(35));
        // 验证 restock 同样读取共享迁移权限。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, restockSnapshot.getInventorySlotMode(9));
        // 关闭服务并释放后台线程。
        loadingService.flushAndClose(5L);
    }

    /**
     * 新四态 JSON 必须优先于同一槽位的旧锁定数组，且非法条目不能污染配置。
     *
     * @param temporaryDirectory JUnit 提供的独立临时目录。
     * @throws Exception 玩家 JSON 写入失败时由测试框架报告。
     */
    @Test
    void preferences_jsonMigratesLegacySlotsAndPrefersNewModes(@TempDir Path temporaryDirectory) throws Exception {
        // 创建稳定玩家 UUID。
        UUID playerUuid = UUID.randomUUID();
        // 构建玩家 JSON 文件路径。
        Path playerFile = temporaryDirectory.resolve("data").resolve("players").resolve(playerUuid + ".json");
        // 创建模拟既有配置的目录。
        Files.createDirectories(playerFile.getParent());
        // 新格式覆盖槽位 9，旧数组补充槽位 10，同时携带非法键和值。
        Files.writeString(playerFile, """
                {"version":1,"inventorySlotModes":{"0":"DEPOSIT_ONLY","9":"DISABLED","36":"RESTOCK_ONLY","x":"RESTOCK_ONLY","10":"UNKNOWN"},
                "deposit":{"lockedInventorySlots":[9,10,11.5]}}
                """);
        // 创建服务触发兼容加载。
        PlayerPreferencesService loadingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        // 读取两个操作快照。
        OperationPreferencesSnapshot depositSnapshot = loadingService.snapshot(playerUuid, OperationType.DEPOSIT);
        OperationPreferencesSnapshot restockSnapshot = loadingService.snapshot(playerUuid, OperationType.RESTOCK);

        // 验证新格式槽位 0 的仅整理权限。
        assertEquals(InventorySlotMode.DEPOSIT_ONLY, depositSnapshot.getInventorySlotMode(0));
        // 验证新格式同槽配置优先于旧数组迁移。
        assertEquals(InventorySlotMode.DISABLED, depositSnapshot.getInventorySlotMode(9));
        // 验证旧数组未被新版覆盖的合法整数迁移为仅补货。
        assertEquals(InventorySlotMode.RESTOCK_ONLY, depositSnapshot.getInventorySlotMode(10));
        // 验证非法小数、越界键和未知状态不产生权限配置。
        assertEquals(InventorySlotMode.ALLOW_BOTH, depositSnapshot.getInventorySlotMode(11));
        assertFalse(depositSnapshot.allowsDeposit(36));
        // 验证共享权限在 restock 快照中一致。
        assertEquals(InventorySlotMode.DISABLED, restockSnapshot.getInventorySlotMode(9));
        loadingService.flushAndClose(5L);
    }

    /**
     * 四态更新 API 必须往返持久化，并拒绝非法槽位与重复状态。
     *
     * @param temporaryDirectory JUnit 提供的独立临时目录。
     */
    @Test
    void preferences_slotModeUpdatePersistsAndValidatesBounds(@TempDir Path temporaryDirectory) {
        // 创建首次写入服务与稳定 UUID。
        PlayerPreferencesService savingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        UUID playerUuid = UUID.randomUUID();
        // 配置快捷栏首槽为仅整理。
        assertTrue(savingService.setInventorySlotMode(playerUuid, 0, InventorySlotMode.DEPOSIT_ONLY));
        // 配置主背包末槽为完全禁止。
        assertTrue(savingService.setInventorySlotMode(playerUuid, 35, InventorySlotMode.DISABLED));
        // 验证重复状态和边界外槽位被拒绝。
        assertFalse(savingService.setInventorySlotMode(playerUuid, 0, InventorySlotMode.DEPOSIT_ONLY));
        assertFalse(savingService.setInventorySlotMode(playerUuid, -1, InventorySlotMode.DISABLED));
        assertFalse(savingService.setInventorySlotMode(playerUuid, 36, InventorySlotMode.DISABLED));
        savingService.flushAndClose(5L);

        // 重建服务模拟服务器重启。
        PlayerPreferencesService loadingService = new PlayerPreferencesService(
                temporaryDirectory.resolve("data"), Logger.getLogger("PlayerPreferencesServiceTest"));
        // 读取重载后的共享权限快照。
        OperationPreferencesSnapshot snapshot = loadingService.snapshot(playerUuid, OperationType.RESTOCK);
        // 验证两个配置状态已跨重启恢复。
        assertEquals(Map.of(0, InventorySlotMode.DEPOSIT_ONLY, 35, InventorySlotMode.DISABLED),
                snapshot.getInventorySlotModes());
        loadingService.flushAndClose(5L);
    }
}
