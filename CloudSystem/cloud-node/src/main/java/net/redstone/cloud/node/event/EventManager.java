package net.redstone.cloud.node.event;

import net.redstone.cloud.api.event.CloudEventHandler;
import net.redstone.cloud.api.event.Event;
import net.redstone.cloud.api.event.Listener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventManager {

    private final Map<Class<? extends Event>, List<RegisteredListener>> listeners = new HashMap<>();

    public void registerListeners(Listener listener) {
        for (Method method : listener.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(CloudEventHandler.class) && method.getParameterCount() == 1) {
                Class<?> eventClass = method.getParameterTypes()[0];
                if (Event.class.isAssignableFrom(eventClass)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Event> clazz = (Class<? extends Event>) eventClass;
                    listeners.computeIfAbsent(clazz, k -> new ArrayList<>()).add(new RegisteredListener(listener, method));
                }
            }
        }
    }

    public void unregisterListeners(Listener listener) {
        for (List<RegisteredListener> list : listeners.values()) {
            list.removeIf(rl -> rl.listener.equals(listener));
        }
    }

    public <T extends Event> T callEvent(T event) {
        List<RegisteredListener> list = listeners.get(event.getClass());
        if (list != null) {
            for (RegisteredListener rl : list) {
                try {
                    rl.method.setAccessible(true);
                    rl.method.invoke(rl.listener, event);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return event;
    }

    private static class RegisteredListener {
        final Listener listener;
        final Method method;

        RegisteredListener(Listener listener, Method method) {
            this.listener = listener;
            this.method = method;
        }
    }
}
