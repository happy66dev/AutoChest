package io.github.autochest.integration.playerbackpack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

public final class PlayerBackpackAdapter {
    private final Object api;
    private final ClassLoader apiClassLoader;
    private final Logger logger;
    // 缓存稳定 API 方法，避免每次跨域 mutation 重新扫描反射元数据喵~
    private final Map<String, Method> apiMethods;
    // 缓存 API DTO 构造器，减少高频跨域提交的反射查找喵~
    private final Map<String, Constructor<?>> apiConstructors;

    public PlayerBackpackAdapter(Object api, Logger logger) {
        if (api == null || logger == null) {
            throw new IllegalArgumentException("PlayerBackpack 适配器依赖不能为空喵~");
        }
        this.api = api;
        this.apiClassLoader = api.getClass().getClassLoader();
        this.logger = logger;
        // 初始化可变缓存，缺失方法仍由 invoke 抛出明确异常喵~
        this.apiMethods = new java.util.HashMap<>();
        // 初始化 DTO 构造器缓存，首次调用时按类名和签名填充喵~
        this.apiConstructors = new java.util.HashMap<>();
    }

    public Optional<BackpackSnapshot> loadSnapshot(UUID targetId) {
        if (targetId == null) {
            return Optional.empty();
        }
        try {
            Object nativeSnapshot = invoke("loadSnapshot", targetId);
            if (!(nativeSnapshot instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toSnapshot(optional.get()));
        } catch (Throwable exception) {
            log(Level.WARNING, "loadSnapshot", targetId, exception);
            return Optional.empty();
        }
    }

    public Optional<BackpackOperation> tryBeginOperation(UUID targetId, UUID requesterId, String reason) {
        if (targetId == null || requesterId == null || reason == null || reason.isBlank()) {
            return Optional.empty();
        }
        try {
            Object nativeResult = invoke("tryBeginOperation", targetId, requesterId, reason);
            if (!(nativeResult instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toOperation(optional.get()));
        } catch (Throwable exception) {
            log(Level.WARNING, "tryBeginOperation", targetId, exception);
            return Optional.empty();
        }
    }

    public BackpackOperationFailure saveAndCloseOpenGui(BackpackOperation operation) {
        if (operation == null) {
            return BackpackOperationFailure.PRECONDITION_FAILED;
        }
        try {
            return toFailure(invoke("saveAndCloseOpenGui", operation.nativeHandle()));
        } catch (Throwable exception) {
            log(Level.SEVERE, "saveAndCloseOpenGui", operation.targetId(), exception);
            return BackpackOperationFailure.STORAGE_FAILURE;
        }
    }

    // 在下一 tick 建立快照前确认 provider 已关闭全部目标 GUI 喵~
    public BackpackOperationFailure confirmExternalOperationReady(BackpackOperation operation) {
        // 喵~防御：操作句柄为空时不能确认 GUI 冻结状态喵~
        if (operation == null) {
            // 返回前置条件失败喵~
            return BackpackOperationFailure.PRECONDITION_FAILED;
        }
        try {
            // 调用 provider 的显式 GUI 关闭确认接口喵~
            return toFailure(invoke("confirmExternalOperationReady", operation.nativeHandle()));
        } catch (Throwable exception) {
            // 喵~防御：provider 缺少确认接口或运行失败时拒绝跨域任务喵~
            log(Level.SEVERE, "confirmExternalOperationReady", operation.targetId(), exception);
            // 返回服务不可用喵~
            return BackpackOperationFailure.SERVICE_UNAVAILABLE;
        }
    }

    // 应用受 revision 和 before-image 保护的幂等 mutation 喵~
    public BackpackMutationResult applyMutation(BackpackOperation operation, BackpackMutationRequest request) {
        // 喵~防御：操作句柄和 mutation 请求不能为空喵~
        if (operation == null || request == null) {
            return failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE, "操作或请求为空喵~");
        }
        try {
            return toMutationResult(invoke("applyMutation", operation.nativeHandle(), toNativeRequest(request)));
        } catch (Throwable exception) {
            log(Level.SEVERE, "applyMutation", request.mutationId(), exception);
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, exception.getMessage());
        }
    }

    public BackpackMutationResult prepareMutation(BackpackOperation operation, BackpackMutationRequest request,
                                                   BackpackContainerMutation containerMutation) {
        if (operation == null || request == null || containerMutation == null) {
            return failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE, "journal 准备参数为空喵~");
        }
        try {
            Object nativeRequest = toNativeRequest(request);
            Object nativeContainerMutation = toNativeContainerMutation(containerMutation);
            return toMutationResult(invoke("prepareMutation", operation.nativeHandle(), nativeRequest, nativeContainerMutation));
        } catch (Throwable exception) {
            log(Level.SEVERE, "prepareMutation", request.mutationId(), exception);
            return failed(BackpackMutationResult.Status.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    public BackpackOperationFailure markContainerApplied(BackpackOperation operation, UUID mutationId) {
        if (operation == null || mutationId == null) {
            return BackpackOperationFailure.PRECONDITION_FAILED;
        }
        try {
            return toFailure(invoke("markContainerApplied", operation.nativeHandle(), mutationId));
        } catch (Throwable exception) {
            log(Level.SEVERE, "markContainerApplied", mutationId, exception);
            return BackpackOperationFailure.STORAGE_FAILURE;
        }
    }

    public BackpackMutationResult applyCompensation(BackpackOperation operation, UUID originalMutationId,
                                                     BackpackMutationRequest request) {
        if (operation == null || originalMutationId == null || request == null) {
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, "补偿参数为空喵~");
        }
        try {
            return toMutationResult(invoke("applyCompensation", operation.nativeHandle(), originalMutationId,
                    toNativeRequest(request)));
        } catch (Throwable exception) {
            log(Level.SEVERE, "applyCompensation", originalMutationId, exception);
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, exception.getMessage());
        }
    }

    public void finish(BackpackOperation operation) {
        if (operation == null) {
            return;
        }
        try {
            invoke("finishOperation", operation.nativeHandle());
        } catch (Throwable exception) {
            log(Level.WARNING, "finishOperation", operation.targetId(), exception);
        }
    }

    private Object invoke(String methodName, Object... arguments) throws ReflectiveOperationException {
        // 优先使用缓存方法，避免每次调用重复遍历 API 方法列表喵~
        Method cachedMethod = apiMethods.get(methodName + "#" + arguments.length);
        // 首次调用时加载 API 接口并查找精确参数数量的方法喵~
        if (cachedMethod == null) {
            // 仅在首次调用加载 API 类型，保持缺少 API 时安全失败喵~
            Class<?> apiInterface = Class.forName("com.playerbackpack.api.PlayerBackpackApi", false, apiClassLoader);
            // 遍历公开方法并缓存匹配项喵~
            for (Method method : apiInterface.getMethods()) {
                // 方法名和参数数量共同确定当前桥接入口喵~
                if (method.getName().equals(methodName) && method.getParameterCount() == arguments.length) {
                    // 缓存当前方法供后续调用复用喵~
                    cachedMethod = method;
                    apiMethods.put(methodName + "#" + arguments.length, method);
                    break;
                }
            }
        }
        // 喵~防御：API 缺少指定方法时保持明确异常和原有降级行为喵~
        if (cachedMethod == null) {
            // 抛出反射方法不存在异常喵~
            throw new NoSuchMethodException(methodName);
        }
        // 调用缓存方法并返回 provider 原始结果喵~
        return cachedMethod.invoke(api, arguments);
    }

    private Object toNativeRequest(BackpackMutationRequest request) throws ReflectiveOperationException {
        Class<?> requestClass = load("BackpackMutationRequest");
        Class<?> directionClass = load("BackpackMutationDirection");
        Object direction = Enum.valueOf(directionClass.asSubclass(Enum.class), request.direction().name());
        // 生成稳定构造器缓存键，避免重复反射查找喵~
        String constructorKey = requestClass.getName() + "#request";
        // 从缓存读取请求 DTO 构造器喵~
        Constructor<?> constructor = apiConstructors.get(constructorKey);
        // 首次调用时按精确签名查找并缓存构造器喵~
        if (constructor == null) {
            constructor = requestClass.getConstructor(UUID.class, UUID.class, directionClass, long.class,
                    int.class, ItemStack.class, ItemStack.class, int.class);
            apiConstructors.put(constructorKey, constructor);
        }
        // 用缓存构造器生成 provider 请求 DTO 喵~
        return constructor.newInstance(request.mutationId(), request.targetId(), direction, request.expectedRevision(),
                request.logicalSlot(), request.expectedBefore(), request.requestedAfter(), request.movedAmount());
    }

    private Object toNativeContainerMutation(BackpackContainerMutation mutation) throws ReflectiveOperationException {
        // 加载容器描述 DTO 类型喵~
        Class<?> descriptorClass = load("BackpackContainerDescriptor");
        // 生成稳定构造器缓存键，避免重复反射查找喵~
        String descriptorConstructorKey = descriptorClass.getName() + "#descriptor";
        // 从缓存读取容器描述构造器喵~
        Constructor<?> descriptorConstructor = apiConstructors.get(descriptorConstructorKey);
        // 首次调用时按精确签名查找并缓存构造器喵~
        if (descriptorConstructor == null) {
            descriptorConstructor = descriptorClass.getConstructor(UUID.class, int.class, int.class, int.class, int.class);
            apiConstructors.put(descriptorConstructorKey, descriptorConstructor);
        }
        // 使用缓存构造器生成 provider 容器描述喵~
        BackpackContainerDescriptor descriptor = mutation.descriptor();
        Object nativeDescriptor = descriptorConstructor.newInstance(descriptor.worldId(), descriptor.x(), descriptor.y(),
                descriptor.z(), descriptor.slot());
        // 加载容器 mutation DTO 类型喵~
        Class<?> mutationClass = load("BackpackContainerMutation");
        // 生成稳定构造器缓存键喵~
        String mutationConstructorKey = mutationClass.getName() + "#mutation";
        // 从缓存读取容器 mutation 构造器喵~
        Constructor<?> mutationConstructor = apiConstructors.get(mutationConstructorKey);
        // 首次调用时查找并缓存容器 mutation 构造器喵~
        if (mutationConstructor == null) {
            mutationConstructor = mutationClass.getConstructor(descriptorClass, ItemStack.class, ItemStack.class);
            apiConstructors.put(mutationConstructorKey, mutationConstructor);
        }
        // 使用缓存构造器生成 provider mutation DTO 喵~
        return mutationConstructor.newInstance(nativeDescriptor, mutation.expectedBefore(), mutation.requestedAfter());
    }

    private BackpackOperation toOperation(Object nativeOperation) throws ReflectiveOperationException {
        UUID targetId = (UUID) property(nativeOperation, "targetId");
        UUID requesterId = (UUID) property(nativeOperation, "requesterId");
        String token = (String) property(nativeOperation, "token");
        long revision = ((Number) property(nativeOperation, "initialRevision")).longValue();
        return new BackpackOperation(targetId, requesterId, token, revision, nativeOperation);
    }

    private BackpackSnapshot toSnapshot(Object nativeSnapshot) throws ReflectiveOperationException {
        UUID playerId = (UUID) property(nativeSnapshot, "playerId");
        int capacity = ((Number) property(nativeSnapshot, "capacity")).intValue();
        long revision = ((Number) property(nativeSnapshot, "revision")).longValue();
        Object nativeItems = property(nativeSnapshot, "items");
        NavigableMap<Integer, ItemStack> items = new TreeMap<>();
        if (nativeItems instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Number slot && entry.getValue() instanceof ItemStack item) {
                    items.put(slot.intValue(), item.clone());
                }
            }
        }
        return new BackpackSnapshot(playerId, capacity, revision, items);
    }

    private BackpackMutationResult toMutationResult(Object nativeResult) throws ReflectiveOperationException {
        if (nativeResult == null) {
            return failed(BackpackMutationResult.Status.RECONCILIATION_REQUIRED, "provider 返回空 mutation 结果喵~");
        }
        Object nativeStatus = property(nativeResult, "status");
        BackpackMutationResult.Status status = switch (String.valueOf(nativeStatus)) {
            case "APPLIED", "IDEMPOTENT_REPLAY" -> BackpackMutationResult.Status.APPLIED;
            case "RECONCILIATION_REQUIRED" -> BackpackMutationResult.Status.RECONCILIATION_REQUIRED;
            case "SERVICE_UNAVAILABLE" -> BackpackMutationResult.Status.SERVICE_UNAVAILABLE;
            default -> BackpackMutationResult.Status.FAILED;
        };
        long revision = ((Number) property(nativeResult, "newRevision")).longValue();
        int movedAmount = ((Number) property(nativeResult, "movedAmount")).intValue();
        Object nativeSnapshot = property(nativeResult, "snapshot");
        BackpackSnapshot snapshot = nativeSnapshot == null ? null : toSnapshot(nativeSnapshot);
        Object diagnostic = property(nativeResult, "diagnostic");
        return new BackpackMutationResult(status, revision, movedAmount, snapshot,
                diagnostic == null ? null : String.valueOf(diagnostic));
    }

    private BackpackOperationFailure toFailure(Object nativeFailure) {
        if (nativeFailure == null) {
            return BackpackOperationFailure.SERVICE_UNAVAILABLE;
        }
        try {
            return BackpackOperationFailure.valueOf(String.valueOf(nativeFailure));
        } catch (IllegalArgumentException exception) {
            return BackpackOperationFailure.SERVICE_UNAVAILABLE;
        }
    }

    private Object property(Object object, String name) throws ReflectiveOperationException {
        return object.getClass().getMethod(name).invoke(object);
    }

    private Class<?> load(String simpleName) throws ClassNotFoundException {
        return Class.forName("com.playerbackpack.api." + simpleName, false, apiClassLoader);
    }

    private BackpackMutationResult failed(BackpackMutationResult.Status status, String diagnostic) {
        return new BackpackMutationResult(status, 0L, 0, null, diagnostic);
    }

    private void log(Level level, String operation, Object target, Throwable exception) {
        logger.log(level, "[AutoChest] PlayerBackpack " + operation + " 失败，目标=" + target + " 喵~", exception);
    }
}
