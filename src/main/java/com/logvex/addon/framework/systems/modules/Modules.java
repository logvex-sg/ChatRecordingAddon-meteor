package com.logvex.addon.framework.systems.modules;

import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.orbit.EventBus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of all modules, and the process-wide {@link EventBus} they share.
 *
 * <p>Modules are stored by class, so {@link #get(Class)} is an O(1) lookup and a module can never
 * be registered twice under the same type.
 */
public final class Modules {
    private static final Modules INSTANCE = new Modules();

    // One bus for the whole addon: modules, HUD and the recorder all publish and listen here.
    private final EventBus eventBus = Addon.EVENT_BUS;
    private final Map<Class<? extends Module>, Module> modules = new LinkedHashMap<>();
    private final List<Module> active = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();

    private Modules() {
    }

    public static Modules get() {
        return INSTANCE;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public void add(Module module) {
        if (module == null) return;
        modules.putIfAbsent(module.getClass(), module);
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> klass) {
        return (T) modules.get(klass);
    }

    public Module get(String name) {
        for (Module module : modules.values()) {
            if (module.name.equalsIgnoreCase(name) || module.title.equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    public Collection<Module> getAll() {
        return modules.values();
    }

    public List<Module> getList() {
        return new ArrayList<>(modules.values());
    }

    public List<Module> getGroup(Category category) {
        List<Module> group = new ArrayList<>();
        for (Module module : modules.values()) {
            if (module.category.equals(category)) group.add(module);
        }
        return group;
    }

    public List<Module> getActive() {
        return List.copyOf(active);
    }

    public boolean isActive(Class<? extends Module> klass) {
        Module module = modules.get(klass);
        return module != null && module.isActive();
    }

    void addActive(Module module) {
        if (!active.contains(module)) active.add(module);
    }

    void removeActive(Module module) {
        active.remove(module);
    }

    public void disableAll() {
        for (Module module : List.copyOf(active)) {
            if (module.isActive()) module.toggle();
        }
    }

    // ---------------------------------------------------------------- categories

    public void registerCategory(Category category) {
        if (category != null && !categories.contains(category)) categories.add(category);
    }

    public List<Category> categories() {
        return List.copyOf(categories);
    }
}
