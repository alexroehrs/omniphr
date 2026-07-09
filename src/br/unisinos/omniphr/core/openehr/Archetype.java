package br.unisinos.omniphr.core.openehr;

import br.unisinos.omniphr.core.HealthCategory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simplified representation of an openEHR archetype instance. The flexible,
 * archetype-based structure of openEHR follows the premise of datablocks,
 * which fits the purpose of having health datablocks chained on a P2P
 * network.
 *
 * An instance carries the archetype identifier (in the standard openEHR
 * notation, e.g. {@code openEHR-EHR-OBSERVATION.blood_pressure.v1}) and the
 * data items addressed by their archetype node paths.
 */
public final class Archetype {

    private final String archetypeId;
    private final HealthCategory category;
    private final Map<String, String> items;

    public Archetype(String archetypeId, HealthCategory category, Map<String, String> items) {
        this.archetypeId = archetypeId;
        this.category = category;
        this.items = new LinkedHashMap<>(items);
    }

    public String getArchetypeId() {
        return archetypeId;
    }

    public HealthCategory getCategory() {
        return category;
    }

    public Map<String, String> getItems() {
        return Collections.unmodifiableMap(items);
    }

    /**
     * Canonical serialization used for hashing, encryption and digital
     * signature, so that the same content always produces the same bytes.
     */
    public String toCanonicalString() {
        StringBuilder sb = new StringBuilder();
        sb.append("archetype_id=").append(archetypeId).append('\n');
        sb.append("category=").append(category).append('\n');
        items.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        return sb.toString();
    }

    public static Archetype fromCanonicalString(String canonical) {
        String archetypeId = null;
        HealthCategory category = null;
        Map<String, String> items = new LinkedHashMap<>();
        for (String line : canonical.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf('=');
            String key = line.substring(0, idx);
            String value = line.substring(idx + 1);
            if (key.equals("archetype_id")) {
                archetypeId = value;
            } else if (key.equals("category")) {
                category = HealthCategory.valueOf(value);
            } else {
                items.put(key, value);
            }
        }
        return new Archetype(archetypeId, category, items);
    }

    @Override
    public String toString() {
        return archetypeId + " " + items;
    }
}
