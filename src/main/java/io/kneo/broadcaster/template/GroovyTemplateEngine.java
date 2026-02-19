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

    public String render(String script, Map<String, Object> context, String draftSlug) {
        try {
            Bindings bindings = new SimpleBindings();
            if (context != null) {
                bindings.putAll(context);
            }
            engine.put(ScriptEngine.FILENAME, String.format("%s.groovy", draftSlug));
            return String.valueOf(engine.eval(script, bindings));
        } catch (Exception e) {
            String msg = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            
            // Log script context for debugging
            StringBuilder contextInfo = new StringBuilder();
            if (context != null) {
                contextInfo.append("Context variables: ");
                context.keySet().forEach(key -> {
                    Object value = context.get(key);
                    String valueStr = value != null ? value.getClass().getSimpleName() : "null";
                    contextInfo.append(key).append("=").append(valueStr).append(", ");
                });
                if (contextInfo.length() > 2) {
                    contextInfo.setLength(contextInfo.length() - 2); // Remove trailing comma
                }
            }
            
            String errorMsg = String.format("Failed to evaluate Groovy script '%s': %s. %s", draftSlug, msg, contextInfo);
            throw new RuntimeException(errorMsg, e);
        }
    }
}
