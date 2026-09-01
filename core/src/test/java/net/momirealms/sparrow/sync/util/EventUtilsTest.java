package net.momirealms.sparrow.sync.util;

import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EventUtilsTest {

    @Test
    void findsRegisteredListenerForAnyEventType() throws EventException {
        ProbeEvent event = new ProbeEvent();
        ProbeListener listener = new ProbeListener();
        EventExecutor executor = (registered, dispatched) -> ((ProbeListener) registered).handle((ProbeEvent) dispatched);
        RegisteredListener registration = new RegisteredListener(listener, executor, EventPriority.NORMAL, plugin(), false);
        ProbeEvent.getHandlerList().register(registration);
        try {
            registration.callEvent(event);

            assertNotNull(listener.callSite);
            assertEquals("ProbePlugin", listener.callSite.plugin());
            assertEquals(ProbeListener.class.getName() + "#handle", listener.callSite.listener());
        } finally {
            ProbeEvent.getHandlerList().unregister(registration);
        }
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName", "toString" -> "ProbePlugin";
            case "isEnabled" -> true;
            case "hashCode" -> 17;
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final class ProbeListener implements Listener {
        private EventUtils.ListenerCallSite callSite;

        private void handle(ProbeEvent event) {
            this.callSite = EventUtils.findListenerCallSite(event);
        }
    }

    private static final class ProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        @NotNull
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        @NotNull
        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
