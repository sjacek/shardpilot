package io.github.sjacek.shardpilot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Shared JSON/YAML field assembly, independent of selection and diagnostic file storage. */
final class DocumentMaps {
    private DocumentMaps() { }

    /**
     * Internal callers supply alternating string keys and values. Preserve insertion order for
     * canonical policy hashes and retain explicit null diagnostic values rather than dropping fields.
     */
    static Map<String, Object> object(Object @NonNull ... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
}
