package net.momirealms.sparrow.sync.session;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

sealed interface PlayerDataPreload {

    record Ready(@NotNull Optional<CompoundTag> data) implements PlayerDataPreload {
    }

    /** 本地数据读取失败, 登录流程按空数据继续. */
    Fallback FALLBACK = new Fallback();

    record Fallback() implements PlayerDataPreload {
    }
}
