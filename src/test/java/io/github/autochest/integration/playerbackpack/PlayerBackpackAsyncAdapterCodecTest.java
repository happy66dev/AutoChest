package io.github.autochest.integration.playerbackpack;

// 导入内存字节输入流以独立读取 Adapter 生成的 BLOB 喵~
import java.io.ByteArrayInputStream;
// 导入反射方法类型以调用 adapter 私有编码边界喵~
import java.lang.reflect.Method;
// 导入 UUID 类型以构造适配器测试 provider 身份喵~
import java.util.UUID;
// 导入 JUnit 测试注解喵~
import org.junit.jupiter.api.Test;
// 导入 Bukkit 对象输入流以独立验证 PlayerBackpack 的 legacy 解码合约喵~
import org.bukkit.util.io.BukkitObjectInputStream;
// 导入 Bukkit 对象输出流以构造真实 PlayerBackpack 格式 fixture 喵~
import org.bukkit.util.io.BukkitObjectOutputStream;
// 导入 Bukkit 物品类型喵~
import org.bukkit.inventory.ItemStack;
// 导入 Mockito 配置工具以创建可 Java 序列化的物品替身喵~
import static org.mockito.Mockito.mock;
// 导入 Mockito 行为配置工具喵~
import static org.mockito.Mockito.when;
// 导入 Mockito 序列化设置工具喵~
import static org.mockito.Mockito.withSettings;
// 导入不为空断言喵~
import static org.junit.jupiter.api.Assertions.assertNotNull;
// 导入抛异常断言喵~
import static org.junit.jupiter.api.Assertions.assertThrows;
// 导入真值断言喵~
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证 AutoChest 与 PlayerBackpack v2 使用相同 Bukkit 对象流物品 BLOB 格式喵~
class PlayerBackpackAsyncAdapterCodecTest {

    // 验证 Adapter 写出的 BLOB 可由同一 PlayerBackpack 对象流协议读回喵~
    @Test
    void playerBackpackObjectBlob_roundTripsSerializableItem() throws Exception {
        // 创建适配器实例以通过其协议边界编码和解码喵~
        PlayerBackpackAsyncAdapter adapter = new PlayerBackpackAsyncAdapter(new NoopProvider(),
                java.util.logging.Logger.getLogger("PlayerBackpackAsyncAdapterCodecTest"));
        // 创建可 Java 序列化的 Bukkit 物品替身，避免单测 JVM 依赖 Paper 注册表喵~
        ItemStack sourceItem = mock(ItemStack.class, withSettings().serializable());
        // 配置源物品为非空物品喵~
        when(sourceItem.isEmpty()).thenReturn(false);
        // 配置克隆返回同一可序列化替身，满足生产编码的隔离副本语义喵~
        when(sourceItem.clone()).thenReturn(sourceItem);
        // 反射读取生产私有编码方法，避免为测试扩大运行时 API 面喵~
        Method encodeMethod = PlayerBackpackAsyncAdapter.class.getDeclaredMethod("encodePlayerBackpackItem", ItemStack.class);
        // 允许测试调用已验证的内部协议边界喵~
        encodeMethod.setAccessible(true);
        // 反射读取生产私有解码方法喵~
        Method decodeMethod = PlayerBackpackAsyncAdapter.class.getDeclaredMethod("decodePlayerBackpackItem", byte[].class);
        // 允许测试调用已验证的内部协议边界喵~
        decodeMethod.setAccessible(true);
        // 使用生产编码实现生成 v2 ItemPayload 将携带的 BLOB 喵~
        byte[] encodedBytes = (byte[]) encodeMethod.invoke(adapter, sourceItem);
        // 断言对象流 BLOB 已生成，禁止回归为空 payload 喵~
        assertTrue(encodedBytes.length > 4);
        // 使用 PlayerBackpack 同等 Bukkit 对象流独立读取编码结果，避免测试仅验证同一错误实现喵~
        try (ByteArrayInputStream byteInput = new ByteArrayInputStream(encodedBytes);
             BukkitObjectInputStream objectInput = new BukkitObjectInputStream(byteInput)) {
            // 读取 PlayerBackpack codec 应能恢复的对象喵~
            Object providerDecodedItem = objectInput.readObject();
            // 断言生产输出与 provider 的对象流协议兼容喵~
            assertTrue(providerDecodedItem instanceof ItemStack);
        }
        // 使用生产解码实现读取同一 BLOB 喵~
        ItemStack decodedItem = (ItemStack) decodeMethod.invoke(adapter, (Object) encodedBytes);
        // 断言对象流格式能恢复有效 ItemStack，而不是进入 Paper NBT/GZIP 路径喵~
        assertNotNull(decodedItem);
    }

    // 验证损坏 BLOB 被 fail-closed 拒绝，避免跨域 mutation 把坏数据当成空槽喵~
    @Test
    void playerBackpackObjectBlob_rejectsInvalidBytes() throws Exception {
        // 创建适配器实例以调用生产解码边界喵~
        PlayerBackpackAsyncAdapter adapter = new PlayerBackpackAsyncAdapter(new NoopProvider(),
                java.util.logging.Logger.getLogger("PlayerBackpackAsyncAdapterCodecTest"));
        // 反射读取生产私有解码方法喵~
        Method decodeMethod = PlayerBackpackAsyncAdapter.class.getDeclaredMethod("decodePlayerBackpackItem", byte[].class);
        // 允许测试验证协议防御分支喵~
        decodeMethod.setAccessible(true);
        // 断言随机非对象流字节会被包装成受控反射调用异常喵~
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> decodeMethod.invoke(adapter, (Object) new byte[]{1, 2, 3, 4}));
    }

    // 提供不会被编解码测试调用的最小 provider 实例喵~
    private static final class NoopProvider {

        // 返回随机身份仅用于确保测试 provider 为普通可加载对象喵~
        public UUID providerId() {
            // 返回独立 UUID，生产测试不会调用此方法喵~
            return UUID.randomUUID();
        }
    }
}
