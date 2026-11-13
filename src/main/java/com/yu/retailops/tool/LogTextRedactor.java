package com.yu.retailops.tool;

import java.util.*;
import java.util.regex.*;

/** Diagnostic-only projection. Payload, SQL, identifiers and arbitrary business text are excluded. */
final class LogTextRedactor {
    private static final Pattern EXCEPTION=Pattern.compile("^(?:Caused by: |Suppressed: )?(?:[\\w$]+\\.)*[A-Z][\\w$]*(?:Exception|Error)(?::.*)?$");
    static List<String> diagnostics(String text) {
        List<String> result=new ArrayList<>();
        for(String raw:text.split("\\R")) {
            String s=raw.strip();
            if(!EXCEPTION.matcher(s).matches() && !s.matches("at [\\w.$]+\\([^)]{1,180}\\)"))continue;
            if(s.matches("(?is).*(?:payload|password|passwd|secret|token|authorization|appkey|address|姓名|地址|手机|电话|select |insert |update |delete |\\{|\\[).*")) {
                int colon=s.indexOf(':');s=colon>=0?s.substring(0,colon)+": [DETAIL_REDACTED]":"[DETAIL_REDACTED]";
            }
            s=s.replaceAll("(['\"]).*?\\1","[VALUE]")
                .replaceAll("(?i)(?:https?://|jdbc:)[^\\s]+","[ENDPOINT]")
                .replaceAll("[\\w.+-]+@[\\w.-]+","[EMAIL]")
                .replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b","[IP]")
                .replaceAll("\\b\\d{7,}\\b","[ID]")
                .replaceAll("(?i)\\b[\\w-]+\\s*=\\s*[^\\s,;]+","[KEY_VALUE]");
            if(s.length()>220)s=s.substring(0,220)+"[TRUNCATED]";
            result.add(s);if(result.size()==8)break;
        }
        return List.copyOf(result);
    }
}
