package com.yu.retailops.tool;

import com.yu.retailops.domain.Diagnosis.*;
import java.util.*;

/** Server-owned read-only tool contracts. Model input cannot change execution policy. */
public final class ToolRegistry {
    @FunctionalInterface public interface Handler {
        ToolResult execute(Input input, List<Evidence> history) throws Exception;
    }
    public record Definition(String name, String version, String purpose, long timeoutMillis,
                             int maxResultCharacters, boolean simulated, Handler handler) {
        public Definition {
            if (name == null || name.isBlank() || version == null || version.isBlank()
                    || purpose == null || purpose.isBlank() || timeoutMillis < 1 || timeoutMillis > 30000
                    || maxResultCharacters < 1 || handler == null) throw new IllegalArgumentException("Invalid tool definition");
        }
    }
    private final Map<String,Definition> definitions;
    public ToolRegistry(List<Definition> tools) {
        Map<String,Definition> map=new LinkedHashMap<>();
        for(var tool:tools) if(map.putIfAbsent(tool.name(),tool)!=null) throw new IllegalArgumentException("Duplicate tool");
        definitions=Collections.unmodifiableMap(map);
    }
    public Definition require(String name) {
        var tool=definitions.get(name);
        if(tool==null)throw new SecurityException("TOOL_NOT_ALLOWLISTED");
        return tool;
    }
    public List<Definition> all() { return List.copyOf(definitions.values()); }
}
