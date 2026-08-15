package io.github.autochest.preference;

import io.github.autochest.container.ContainerIdentity;
import io.github.autochest.task.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        // 验证 restock 的独立黑名单被准确恢复。
        assertFalse(restockSnapshot.allows(ContainerIdentity.ContainerType.ENDER_CHEST));
        // 验证 deposit 锁定槽位跨保存与重载准确恢复。
        assertEquals(Set.of(9, 35), depositSnapshot.getLockedInventorySlots());
        // 验证 restock 不继承仅供 deposit 使用的锁定槽位。
        assertTrue(restockSnapshot.getLockedInventorySlots().isEmpty());
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
        // 读取 restock 快照，验证其始终忽略锁定字段。
        OperationPreferencesSnapshot restockSnapshot = loadingService.snapshot(playerUuid, OperationType.RESTOCK);

        // 验证小数 9.5 没有被截断为槽位 9，而整数 9 和 35 被保留。
        assertEquals(Set.of(9, 35), depositSnapshot.getLockedInventorySlots());
        // 验证 restock 不接受锁定格配置。
        assertTrue(restockSnapshot.getLockedInventorySlots().isEmpty());
        // 关闭服务并释放后台线程。
        loadingService.flushAndClose(5L);
    }
}
