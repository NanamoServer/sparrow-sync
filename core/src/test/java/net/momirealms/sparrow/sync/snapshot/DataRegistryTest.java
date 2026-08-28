package net.momirealms.sparrow.sync.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataRegistryTest {
    private static final DataKey A = DataKey.of("sparrow", "alpha");
    private static final DataKey B = DataKey.of("sparrow", "bravo");
    private static final DataKey C = DataKey.of("sparrow", "charlie");

    @Test
    void applyOrderPutsDependencyBeforeDependent() {
        // 准备: bravo 依赖 alpha, charlie 依赖 bravo
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(C, StorageFormat.STRUCTURED, false, Set.of(B)));
        registry.register(DataRegistration.of(B, StorageFormat.STRUCTURED, false, Set.of(A)));
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        // 执行与断言: 链式依赖按 a -> b -> c 输出
        assertEquals(List.of(A, B, C), registry.applyOrder());
    }

    @Test
    void applyOrderIsDeterministicRegardlessOfRegistrationOrder() {
        // 准备: 两个注册表以相反顺序注册同一批无依赖类型
        DataRegistry first = new DataRegistry();
        first.register(DataRegistration.of(C, StorageFormat.STRUCTURED));
        first.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        first.register(DataRegistration.of(B, StorageFormat.STRUCTURED));
        DataRegistry second = new DataRegistry();
        second.register(DataRegistration.of(B, StorageFormat.STRUCTURED));
        second.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        second.register(DataRegistration.of(C, StorageFormat.STRUCTURED));
        // 执行与断言: 结果一致且为字典序
        assertEquals(List.of(A, B, C), first.applyOrder());
        assertEquals(first.applyOrder(), second.applyOrder());
    }

    @Test
    void applyOrderFailsFastOnThreeNodeCycle() {
        // 准备: a -> b -> c -> a 的三元环
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED, false, Set.of(C)));
        registry.register(DataRegistration.of(B, StorageFormat.STRUCTURED, false, Set.of(A)));
        registry.register(DataRegistration.of(C, StorageFormat.STRUCTURED, false, Set.of(B)));
        // 执行与断言: 异常信息给出闭合的环路径, x -> y 表示 x 依赖 y
        IllegalStateException exception = assertThrows(IllegalStateException.class, registry::applyOrder);
        assertTrue(exception.getMessage().contains("sparrow:alpha -> sparrow:charlie -> sparrow:bravo -> sparrow:alpha"));
    }

    @Test
    void cycleReportNamesOnlyCycleNodes() {
        // 准备: 三元环 + 一个依赖环节点的下游 delta
        DataKey delta = DataKey.of("sparrow", "delta");
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED, false, Set.of(C)));
        registry.register(DataRegistration.of(B, StorageFormat.STRUCTURED, false, Set.of(A)));
        registry.register(DataRegistration.of(C, StorageFormat.STRUCTURED, false, Set.of(B)));
        registry.register(DataRegistration.of(delta, StorageFormat.STRUCTURED, false, Set.of(A)));
        // 执行与断言: 只点名环上节点, 被环挡住的下游不背锅
        IllegalStateException exception = assertThrows(IllegalStateException.class, registry::applyOrder);
        assertTrue(exception.getMessage().contains(A.asString()));
        assertTrue(exception.getMessage().contains(B.asString()));
        assertTrue(exception.getMessage().contains(C.asString()));
        assertFalse(exception.getMessage().contains(delta.asString()));
    }

    @Test
    void applyOrderIgnoresUnregisteredDependencies() {
        // 准备: bravo 依赖一个从未注册的 key
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(B, StorageFormat.STRUCTURED, false, Set.of(DataKey.of("sparrow", "missing"))));
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        // 执行与断言: 未注册依赖不参与排序
        assertEquals(List.of(A, B), registry.applyOrder());
    }

    @Test
    void registerRejectsDuplicateKey() {
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        assertThrows(IllegalStateException.class, () -> registry.register(DataRegistration.of(A, StorageFormat.BINARY)));
    }

    @Test
    void frozenRegistryRejectsRegistration() {
        // MC 注册表同款生命周期: 冻结后注册窗口关闭
        DataRegistry registry = new DataRegistry();
        registry.register(DataRegistration.of(A, StorageFormat.STRUCTURED));
        registry.freeze();

        assertThrows(IllegalStateException.class, () -> registry.register(DataRegistration.of(B, StorageFormat.STRUCTURED)));
        assertTrue(registry.frozen());
        assertTrue(registry.registered(A));
    }
}
