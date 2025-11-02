package io.kneo.broadcaster.template;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;

import java.util.Map;
import java.util.stream.Collectors;

public class GroovyTemplateEngine {
    private final ScriptEngine engine;

    public GroovyTemplateEngine() {
        ScriptEngineManager manager = new ScriptEngineManager();

        ScriptEngine eng = manager.getEngineByName("groovy");
        if (eng == null) eng = manager.getEngineByExtension("groovy");

        if (eng == null) {
            String available = manager.getEngineFactories().stream()
                    .map(ScriptEngineFactory::getEngineName)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "Groovy scripting engine not found. Ensure groovy is on the classpath. " +
                            "Available engines: [" + available + "]");
        }
        this.engine = eng;
    }

    public String render(String script, Map<String, Object> context) {
        try {
            Bindings bindings = new SimpleBindings();
            if (context != null) {
                bindings.putAll(context);
            }
            Object out = engine.eval(script, bindings);
            return out == null ? "" : String.valueOf(out);
        } catch (Exception e) {
            String msg = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            throw new RuntimeException("Failed to evaluate Groovy script: " + msg, e);
        }
    }
}
