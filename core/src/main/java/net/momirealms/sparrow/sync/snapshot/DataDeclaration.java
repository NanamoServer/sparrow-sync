package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 一类同步数据的声明: 标识, 存储形态, 失败等级与应用顺序依赖.
 * 行为实现 (采集/应用) 直接继承本接口, 声明与行为合一; 注册表与编解码层只消费本接口, 不触及行为.
 */
public interface DataDeclaration {

    @NotNull
    DataKey key();

    /**
     * 文档存储中的排布形态
     */
    @NotNull
    StorageFormat storage();

    /**
     * 是否视为关键数据, 关键数据如果应用失败, 则会抛出警告并阻止玩家进入服务器.
     */
    default boolean critical() {
        return false;
    }

    /**
     * 应用顺序上先于本类型的其他数据, 未注册的依赖在排序时被忽略.
     */
    @NotNull
    default Set<DataKey> dependencies() {
        return Set.of();
    }
}
