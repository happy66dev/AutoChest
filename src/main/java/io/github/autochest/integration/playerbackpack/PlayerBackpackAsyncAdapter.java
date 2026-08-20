package io.github.autochest.integration.playerbackpack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

// 通过反射连接 PlayerBackpack v2，避免 AutoChest 静态链接可选插件类型喵~
public final class PlayerBackpackAsyncAdapter {
    // 保存 v2 provider 实例喵~
    private final Object api;
    // 保存 provider 所属类加载器喵~
    private final ClassLoader apiClassLoader;
    // 保存日志出口喵~
    private final Logger logger;
    // 缓存 v2 API 方法喵~
    private final Map<String, Method> apiMethods = new HashMap<>();
    // 缓存 v2 DTO 构造器喵~
    private final Map<String, Constructor<?>> constructors = new HashMap<>();

    // 创建异步 adapter 并校验 provider 喵~
    public PlayerBackpackAsyncAdapter(Object api, Logger logger) {
        // 喵~防御：缺少 provider 或日志时拒绝创建 adapter 喵~
        if (api == null || logger == null) {
            throw new IllegalArgumentException("PlayerBackpack v2 适配器依赖不能为空喵~");
        }
        // 保存 provider 引用喵~
        this.api = api;
        // 使用 provider 的 classloader 隔离可选 API 类型喵~
        this.apiClassLoader = api.getClass().getClassLoader();
        // 保存日志依赖喵~
        this.logger = logger;
    }

    // 异步读取 PlayerBackpack 快照，Bukkit DTO 转换在指定主线程执行喵~
    public CompletionStage<Optional<BackpackSnapshot>> loadSnapshotAsync(UUID targetId, Executor mainThreadExecutor) {
        // 喵~防御：目标或回调执行器为空时返回失败 stage 喵~
        if (targetId == null || mainThreadExecutor == null) {
            return failedStage(new IllegalArgumentException("异步快照参数不能为空喵~"));
        }
        try {
            // 调用 v2 actor-safe 快照方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("loadSnapshotAsync", targetId));
            // 在主线程将 BLOB DTO 解码为 Bukkit ItemStack 喵~
            return nativeStage.thenApplyAsync(this::toSnapshotOptional, mainThreadExecutor);
        } catch (Throwable exception) {
            // 反射或 provider 异常统一转失败 stage 喵~
            log(Level.WARNING, "loadSnapshotAsync", targetId, exception);
            return failedStage(exception);
        }
    }

    // 异步预约目标背包独占操作喵~
    public CompletionStage<Optional<BackpackOperation>> tryBeginOperationAsync(UUID targetId, UUID requesterId,
                                                                                 String reason) {
        // 喵~防御：无效 operation 参数不得进入 provider 喵~
        if (targetId == null || requesterId == null || reason == null || reason.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            // 调用 v2 actor-safe operation 方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("tryBeginOperationAsync", targetId, requesterId, reason));
            // 将纯 DTO operation 转成本地句柄喵~
            return nativeStage.thenApply(this::toOperationOptional);
        } catch (Throwable exception) {
            // provider 异常时安全返回空 operation 喵~
            log(Level.WARNING, "tryBeginOperationAsync", targetId, exception);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    // 异步保存、关闭 PlayerBackpack GUI 并激活已预约的 external operation 喵~
    public CompletionStage<BackpackOperationFailure> saveAndCloseOpenGuiAsync(BackpackOperation operation) {
        // 喵~防御：空 operation 时不能关闭未知玩家 GUI 喵~
        if (operation == null) {
            // 返回前置条件失败喵~
            return CompletableFuture.completedFuture(BackpackOperationFailure.PRECONDITION_FAILED);
        }
        try {
            // 调用 provider 的异步 GUI 保存关闭入口喵~
            CompletionStage<?> nativeStage = completionStage(invoke("saveAndCloseOpenGuiAsync", operation.nativeHandle()));
            // 映射稳定 operation failure 枚举喵~
            return nativeStage.thenApply(this::toFailure);
        } catch (Throwable exception) {
            // 反射或 provider 异常时不能假定 GUI 已交出所有权喵~
            log(Level.SEVERE, "saveAndCloseOpenGuiAsync", operation.targetId(), exception);
            return CompletableFuture.completedFuture(BackpackOperationFailure.STORAGE_FAILURE);
        }
    }

    // 异步确认 PlayerBackpack GUI 已关闭且 external operation 仍可写喵~
    public CompletionStage<BackpackOperationFailure> confirmExternalOperationReadyAsync(BackpackOperation operation) {
        // 喵~防御：空 operation 不具备 GUI readiness 查询身份喵~
        if (operation == null) {
            // 返回前置条件失败喵~
            return CompletableFuture.completedFuture(BackpackOperationFailure.PRECONDITION_FAILED);
        }
        try {
            // 调用 provider 的异步 GUI readiness 入口喵~
            CompletionStage<?> nativeStage = completionStage(invoke("confirmExternalOperationReadyAsync",
                    operation.nativeHandle()));
            // 映射稳定 operation failure 枚举喵~
            return nativeStage.thenApply(this::toFailure);
        } catch (Throwable exception) {
            // provider 协议错误或调用失败时拒绝后续跨域 mutation 喵~
            log(Level.SEVERE, "confirmExternalOperationReadyAsync", operation.targetId(), exception);
            return CompletableFuture.completedFuture(BackpackOperationFailure.SERVICE_UNAVAILABLE);
        }
    }

    // 异步准备双域 durable journal，结果快照在主线程解码喵~
    public CompletionStage<BackpackMutationResult> prepareMutationAsync(BackpackOperation operation,
                                                                          BackpackMutationRequest request,
                                                                          BackpackContainerMutation containerMutation,
                                                                          Executor mainThreadExecutor) {
        // 喵~防御：缺少主线程执行器时禁止解码 Bukkit 物品喵~
        if (mainThreadExecutor == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    "主线程执行器为空喵~"));
        }
        // provider 完成后切回 Bukkit 主线程解码 mutation snapshot 喵~
        return prepareMutationPayloadAsync(operation, request, containerMutation)
                .thenApplyAsync(this::toMutationResult, mainThreadExecutor);
    }

    // 异步准备双域 durable journal，内部只传输 provider DTO 喵~
    private CompletionStage<Object> prepareMutationPayloadAsync(BackpackOperation operation,
                                                                  BackpackMutationRequest request,
                                                                  BackpackContainerMutation containerMutation) {
        // 喵~防御：缺少任一 lineage 参数时 fail-closed 喵~
        if (operation == null || request == null || containerMutation == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    "异步 journal 参数为空喵~"));
        }
        try {
            // 将本地 Bukkit 物品转换为 v2 BLOB DTO，仅调用方主线程可执行此方法喵~
            Object nativeRequest = toNativeRequest(request);
            // 将容器 before/after 镜像转换为 v2 BLOB DTO 喵~
            Object nativeContainer = toNativeContainerMutation(containerMutation);
            // 调用非阻塞 v2 journal 方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("prepareMutationAsync", operation.nativeHandle(),
                    nativeRequest, nativeContainer));
            // provider stage 完成后仅转换纯 DTO，调用方在主线程解码快照喵~
            return castObjectStage(nativeStage);
        } catch (Throwable exception) {
            // 映射失败表示未建立可证明 journal，不能继续写入喵~
            log(Level.SEVERE, "prepareMutationAsync", request.mutationId(), exception);
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    exception.getMessage()));
        }
    }

    // 异步应用背包 CAS mutation，并在主线程解码 mutation 快照喵~
    public CompletionStage<BackpackMutationResult> applyMutationAsync(BackpackOperation operation,
                                                                        BackpackMutationRequest request,
                                                                        Executor mainThreadExecutor) {
        // 喵~防御：缺少主线程执行器时禁止 Bukkit BLOB 解码喵~
        if (mainThreadExecutor == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    "主线程执行器为空喵~"));
        }
        // 在 provider 完成后切回 Bukkit 主线程解码快照喵~
        return applyMutationPayloadAsync(operation, request)
                .thenApplyAsync(this::toMutationResult, mainThreadExecutor);
    }

    // 异步应用背包 CAS mutation，内部仅处理 provider DTO 喵~
    private CompletionStage<Object> applyMutationPayloadAsync(BackpackOperation operation,
                                                               BackpackMutationRequest request) {
        // 喵~防御：空参数禁止提交 mutation 喵~
        if (operation == null || request == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    "异步 mutation 参数为空喵~"));
        }
        try {
            // 创建无 Bukkit 引用的 v2 请求 DTO 喵~
            Object nativeRequest = toNativeRequest(request);
            // 调用 actor-backed CAS 方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("applyMutationAsync", operation.nativeHandle(),
                    nativeRequest));
            // 将未解码 provider DTO 交给公开方法切回主线程处理喵~
            return castObjectStage(nativeStage);
        } catch (Throwable exception) {
            // 未知异常必须保留 reconcile 语义喵~
            log(Level.SEVERE, "applyMutationAsync", request.mutationId(), exception);
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED,
                    exception.getMessage()));
        }
    }

    // 异步执行条件补偿 mutation，并在主线程解码结果快照喵~
    public CompletionStage<BackpackMutationResult> applyCompensationAsync(BackpackOperation operation,
                                                                            UUID originalMutationId,
                                                                            BackpackMutationRequest request,
                                                                            Executor mainThreadExecutor) {
        // 喵~防御：缺少主线程执行器时拒绝解码已提交快照喵~
        if (mainThreadExecutor == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE,
                    "主线程执行器为空喵~"));
        }
        // provider 完成后切回主线程解码快照喵~
        return applyCompensationPayloadAsync(operation, originalMutationId, request)
                .thenApplyAsync(this::toMutationResult, mainThreadExecutor);
    }

    // 异步执行条件补偿 mutation，内部只传输 provider DTO 喵~
    private CompletionStage<Object> applyCompensationPayloadAsync(BackpackOperation operation,
                                                                    UUID originalMutationId,
                                                                    BackpackMutationRequest request) {
        // 喵~防御：补偿 lineage 不完整时禁止猜测恢复喵~
        if (operation == null || originalMutationId == null || request == null) {
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED,
                    "异步补偿参数为空喵~"));
        }
        try {
            // 将补偿请求编码为 actor-safe DTO 喵~
            Object nativeRequest = toNativeRequest(request);
            // 调用 v2 条件补偿方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("applyCompensationAsync", operation.nativeHandle(),
                    originalMutationId, nativeRequest));
            // 将未解码 provider DTO 交给公开方法切回主线程处理喵~
            return castObjectStage(nativeStage);
        } catch (Throwable exception) {
            // 补偿调用异常必须进入人工 reconcile 喵~
            log(Level.SEVERE, "applyCompensationAsync", originalMutationId, exception);
            return CompletableFuture.completedFuture(failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED,
                    exception.getMessage()));
        }
    }

    // 异步标记容器已提交并终结 journal 喵~
    public CompletionStage<BackpackOperationFailure> markContainerAppliedAsync(BackpackOperation operation,
                                                                                 UUID mutationId) {
        // 喵~防御：缺少 operation 或 mutation 身份时拒绝推进 journal 喵~
        if (operation == null || mutationId == null) {
            return CompletableFuture.completedFuture(BackpackOperationFailure.PRECONDITION_FAILED);
        }
        try {
            // 调用 v2 journal transition 方法喵~
            CompletionStage<?> nativeStage = completionStage(invoke("markContainerAppliedAsync",
                    operation.nativeHandle(), mutationId));
            // 转换稳定失败枚举喵~
            return nativeStage.thenApply(this::toFailure);
        } catch (Throwable exception) {
            // journal 推进异常时返回存储失败喵~
            log(Level.SEVERE, "markContainerAppliedAsync", mutationId, exception);
            return CompletableFuture.completedFuture(BackpackOperationFailure.STORAGE_FAILURE);
        }
    }

    // 异步释放 operation token，调用方可在任意 completion 线程触发喵~
    public CompletionStage<Void> finishOperationAsync(BackpackOperation operation) {
        // 空 operation 不产生 provider 调用喵~
        if (operation == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            // 调用 v2 token release 方法喵~
            return completionStage(invoke("finishOperationAsync", operation.nativeHandle())).thenApply(ignored -> null);
        } catch (Throwable exception) {
            // 释放失败记录日志但不抛出到任务清理出口喵~
            log(Level.WARNING, "finishOperationAsync", operation.targetId(), exception);
            return CompletableFuture.completedFuture(null);
        }
    }

    // 将 provider 返回的 readiness/operation stage 转为统一 CompletionStage 喵~
    private CompletionStage<?> completionStage(Object value) {
        // 喵~防御：provider 返回非 CompletionStage 视为协议错误喵~
        if (!(value instanceof CompletionStage<?> stage)) {
            throw new IllegalStateException("PlayerBackpack v2 方法未返回 CompletionStage 喵~");
        }
        return stage;
    }

    // 将未知泛型的 provider CompletionStage 规范化为 Object stage 喵~
    private CompletionStage<Object> castObjectStage(CompletionStage<?> stage) {
        // 保留原异常完成语义，仅擦除泛型边界喵~
        return stage.thenApply(value -> (Object) value);
    }

    // 将 v2 Optional 快照 DTO 转成本地快照，调用线程必须是 Bukkit 主线程喵~
    private Optional<BackpackSnapshot> toSnapshotOptional(Object nativeValue) {
        // provider Optional 缺失或空值代表没有快照喵~
        if (!(nativeValue instanceof Optional<?> optional) || optional.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toSnapshot(optional.get()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("PlayerBackpack 快照 DTO 解码失败喵~", exception);
        }
    }

    // 将 v2 Optional operation DTO 转成本地 operation 喵~
    private Optional<BackpackOperation> toOperationOptional(Object nativeValue) {
        // provider 未签发 operation 时保持空 Optional 喵~
        if (!(nativeValue instanceof Optional<?> optional) || optional.isEmpty()) {
            return Optional.empty();
        }
        try {
            Object nativeOperation = optional.get();
            return Optional.of(new BackpackOperation((UUID) property(nativeOperation, "targetId"),
                    (UUID) property(nativeOperation, "requesterId"), String.valueOf(property(nativeOperation, "token")),
                    ((Number) property(nativeOperation, "initialRevision")).longValue(), nativeOperation));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            log(Level.WARNING, "decodeOperation", null, exception);
            return Optional.empty();
        }
    }

    // 将 v2 BLOB 快照 DTO 解码为 Bukkit 物品快照喵~
    private BackpackSnapshot toSnapshot(Object nativeSnapshot) throws ReflectiveOperationException {
        // 读取目标身份与 revision 元数据喵~
        UUID playerId = (UUID) property(nativeSnapshot, "playerId");
        int capacity = ((Number) property(nativeSnapshot, "capacity")).intValue();
        long revision = ((Number) property(nativeSnapshot, "revision")).longValue();
        Object nativeItems = property(nativeSnapshot, "items");
        Map<Integer, ItemStack> items = new HashMap<>();
        // 逐项解码 BLOB，非法条目直接令整个快照失败喵~
        if (nativeItems instanceof Map<?, ?> itemMap) {
            for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                if (!(entry.getKey() instanceof Number slot) || entry.getValue() == null) {
                    continue;
                }
                byte[] payload = (byte[]) property(entry.getValue(), "bytes");
                items.put(slot.intValue(), ItemStack.deserializeBytes(payload));
            }
        }
        // 使用本地快照构造器执行槽位与数量校验喵~
        return new BackpackSnapshot(playerId, capacity, revision, new java.util.TreeMap<>(items));
    }

    // 将本地 mutation 请求编码为 v2 BLOB DTO 喵~
    private Object toNativeRequest(BackpackMutationRequest request) throws ReflectiveOperationException {
        // 加载 v2 请求和 item payload 类型喵~
        Class<?> requestClass = load("v2.BackpackMutationPayload");
        Class<?> itemClass = load("v2.ItemPayload");
        // 找到并缓存请求构造器喵~
        Constructor<?> constructor = constructor(requestClass.getName(), requestClass,
                UUID.class, UUID.class, String.class, long.class, int.class, itemClass, itemClass, int.class);
        // 编码前后镜像，null 保持空槽语义喵~
        Object expectedBefore = toNativeItem(request.expectedBefore(), itemClass);
        Object requestedAfter = toNativeItem(request.requestedAfter(), itemClass);
        // 创建无 Bukkit 引用请求 DTO 喵~
        return constructor.newInstance(request.mutationId(), request.targetId(), request.direction().name(),
                request.expectedRevision(), request.logicalSlot(), expectedBefore, requestedAfter, request.movedAmount());
    }

    // 将本地容器 mutation 编码为 v2 BLOB DTO 喵~
    private Object toNativeContainerMutation(BackpackContainerMutation mutation) throws ReflectiveOperationException {
        // 加载 v2 DTO 类型喵~
        Class<?> descriptorClass = load("v2.ContainerDescriptorPayload");
        Class<?> itemClass = load("v2.ItemPayload");
        Class<?> mutationClass = load("v2.ContainerMutationPayload");
        // 创建容器位置 DTO 喵~
        BackpackContainerDescriptor descriptor = mutation.descriptor();
        Constructor<?> descriptorConstructor = constructor(descriptorClass.getName(), descriptorClass,
                UUID.class, int.class, int.class, int.class, int.class);
        Object nativeDescriptor = descriptorConstructor.newInstance(descriptor.worldId(), descriptor.x(), descriptor.y(),
                descriptor.z(), descriptor.slot());
        // 创建容器 mutation DTO 喵~
        Constructor<?> mutationConstructor = constructor(mutationClass.getName(), mutationClass,
                descriptorClass, itemClass, itemClass);
        return mutationConstructor.newInstance(nativeDescriptor, toNativeItem(mutation.expectedBefore(), itemClass),
                toNativeItem(mutation.requestedAfter(), itemClass));
    }

    // 将 Bukkit ItemStack 编码为 v2 BLOB ItemPayload 喵~
    private Object toNativeItem(ItemStack item, Class<?> itemClass) throws ReflectiveOperationException {
        // 空槽保持 null，不制造伪造物品喵~
        if (item == null) {
            return null;
        }
        // 调用 Bukkit 序列化，必须在主线程执行喵~
        Constructor<?> constructor = constructor(itemClass.getName(), itemClass, byte[].class);
        return constructor.newInstance((Object) item.serializeAsBytes());
    }

    // 将 v2 mutation 结果 DTO 转成本地纯结果喵~
    private BackpackMutationResult toMutationResult(Object nativeResult) {
        // provider 空结果代表状态不确定喵~
        if (nativeResult == null) {
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, "provider 返回空 mutation 结果喵~");
        }
        try {
            String statusName = String.valueOf(property(nativeResult, "status"));
            BackpackMutationResult.Status status = switch (statusName) {
                case "APPLIED", "IDEMPOTENT_REPLAY" -> BackpackMutationResult.Status.APPLIED;
                case "RECONCILIATION_REQUIRED" -> BackpackMutationResult.Status.RECONCILIATION_REQUIRED;
                case "SERVICE_UNAVAILABLE" -> BackpackMutationResult.Status.SERVICE_UNAVAILABLE;
                default -> BackpackMutationResult.Status.FAILED;
            };
            long revision = ((Number) property(nativeResult, "newRevision")).longValue();
            int movedAmount = ((Number) property(nativeResult, "movedAmount")).intValue();
            Object nativeSnapshot = property(nativeResult, "snapshot");
            // 成功或幂等 replay 都必须提供可验证的下一快照基线喵~
            BackpackSnapshot snapshot = nativeSnapshot == null ? null : toSnapshot(nativeSnapshot);
            // 非成功结果不允许伪造快照或移动数量，交由本地结果模型保持 fail-closed 喵~
            Object diagnostic = property(nativeResult, "diagnostic");
            return new BackpackMutationResult(status, revision, movedAmount, snapshot,
                    diagnostic == null ? null : String.valueOf(diagnostic));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, exception.getMessage());
        }
    }

    // 将 provider 失败枚举转换为本地失败枚举喵~
    private BackpackOperationFailure toFailure(Object value) {
        // 空结果统一视作服务不可用喵~
        if (value == null) {
            return BackpackOperationFailure.SERVICE_UNAVAILABLE;
        }
        try {
            return BackpackOperationFailure.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return BackpackOperationFailure.SERVICE_UNAVAILABLE;
        }
    }

    // 查找并缓存反射方法喵~
    private Object invoke(String methodName, Object... arguments) throws ReflectiveOperationException {
        // 根据参数数量查找唯一 v2 方法喵~
        String key = methodName + "#" + arguments.length;
        Method method = apiMethods.get(key);
        if (method == null) {
            // 从 provider 类型查找公开 API 方法喵~
            for (Method candidate : api.getClass().getMethods()) {
                if (candidate.getName().equals(methodName) && candidate.getParameterCount() == arguments.length) {
                    method = candidate;
                    apiMethods.put(key, candidate);
                    break;
                }
            }
        }
        // 喵~防御：协议缺少方法时立即失败，不回退同步 v1 喵~
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        // 调用 provider 方法并返回 CompletionStage 喵~
        return method.invoke(api, arguments);
    }

    // 读取 DTO 属性方法喵~
    private Object property(Object object, String name) throws ReflectiveOperationException {
        if (object == null) {
            throw new IllegalArgumentException("DTO 对象为空喵~");
        }
        return object.getClass().getMethod(name).invoke(object);
    }

    // 按 provider classloader 加载可选 DTO 类型喵~
    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName("com.playerbackpack.api." + name, false, apiClassLoader);
    }

    // 查找并缓存 DTO 构造器喵~
    private Constructor<?> constructor(String key, Class<?> type, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor<?> cached = constructors.get(key);
        if (cached != null) {
            return cached;
        }
        Constructor<?> discovered = type.getConstructor(parameterTypes);
        constructors.put(key, discovered);
        return discovered;
    }

    // 创建 fail-closed mutation 结果喵~
    private BackpackMutationResult failed(BackpackMutationResult.Status status, String diagnostic) {
        return new BackpackMutationResult(status, 0L, 0, null, diagnostic);
    }

    // 创建失败 CompletionStage 喵~
    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(failure);
        return failed;
    }

    // 记录 provider 调用失败喵~
    private void log(Level level, String operation, Object target, Throwable exception) {
        logger.log(level, "[AutoChest] PlayerBackpack v2 " + operation + " 失败，目标=" + target + " 喵~", exception);
    }
}
