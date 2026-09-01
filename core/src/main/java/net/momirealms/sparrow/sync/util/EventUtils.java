package net.momirealms.sparrow.sync.util;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;

import java.lang.StackWalker.StackFrame;
import java.util.Iterator;

public final class EventUtils {
    private static final StackWalker EVENT_CALLER_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private EventUtils() {}

    public static void fireAndForget(Event event) {
        Bukkit.getPluginManager().callEvent(event);
    }

    public static boolean fireAndCheckCancel(Event event) {
        if (!(event instanceof Cancellable cancellable))
            throw new IllegalArgumentException("Only cancellable events are allowed here");
        Bukkit.getPluginManager().callEvent(event);
        return cancellable.isCancelled();
    }

    /**
     * 尽力定位当前正在处理指定事件的监听器调用点.
     *
     * @param event 正在派发的事件
     * @return 监听器所属插件和调用方法, 无法确定的字段使用 {@code unknown}
     */
    @NotNull
    public static ListenerCallSite findListenerCallSite(@NotNull Event event) {
        RegisteredListener[] listeners = event.getHandlers().getRegisteredListeners();
        int listenerCount = listeners.length;
        return EVENT_CALLER_WALKER.walk(frames -> {
            Iterator<StackFrame> iterator = frames.iterator();
            ListenerCallSite fallback = null;
            while (iterator.hasNext()) {
                StackFrame frame = iterator.next();
                Class<?> caller = frame.getDeclaringClass();
                // 监听器类仍在调用栈上时直接归因, 这是最可靠的结果
                for (int i = 0; i < listenerCount; i++) {
                    RegisteredListener listener = listeners[i];
                    if (caller.isAssignableFrom(listener.getListener().getClass())) {
                        return new ListenerCallSite(listener.getPlugin().getName(), caller.getName() + "#" + frame.getMethodName());
                    }
                }
                if (fallback != null || isInfrastructureFrame(caller.getName())) continue;
                // 保留第一个业务调用点, 栈走完仍未精确匹配时使用
                String plugin = "unknown";
                for (int i = 0; i < listenerCount; i++) {
                    RegisteredListener listener = listeners[i];
                    if (listener.getListener().getClass().getClassLoader() == caller.getClassLoader()) {
                        plugin = listener.getPlugin().getName();
                        break;
                    }
                }
                fallback = new ListenerCallSite(plugin, caller.getName() + "#" + frame.getMethodName());
            }
            return fallback != null ? fallback : new ListenerCallSite("unknown", "unknown");
        });
    }

    private static boolean isInfrastructureFrame(@NotNull String className) {
        return className.startsWith("net.momirealms.sparrow.sync.")
                || className.startsWith("org.bukkit.")
                || className.startsWith("io.papermc.")
                || className.startsWith("co.aikar.")
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    public record ListenerCallSite(@NotNull String plugin, @NotNull String listener) {
    }
}
