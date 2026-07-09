package br.unisinos.omniphr.middleware.datablock;

import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.core.openehr.Archetype;
import br.unisinos.omniphr.repository.SemanticRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translator component of the Datablock and Service Module: the input and
 * output gateway of the datablocks, primarily responsible for the
 * interoperability of the model. OmniPHR adopts an open standard for storing
 * the health datablocks on the superpeer, so this component is only used
 * when the healthcare provider uses a different standard.
 *
 * Two translation paths are supported:
 * (1) the provider uses an open standard different from the adopted one
 *     (illustrated here with an HL7 v2-like message, mapped through the
 *     ontology of the semantic database);
 * (2) the provider uses a proprietary standard (illustrated with free-text
 *     records, parsed with a simple NLP routine assisted by the ontology).
 */
public class Translator {

    private final SemanticRepository semanticRepository;

    public Translator(SemanticRepository semanticRepository) {
        this.semanticRepository = semanticRepository;
        seedOntology();
    }

    /**
     * Input gateway: converts a source record to the open standard adopted
     * by OmniPHR (openEHR archetypes). When the provider already uses the
     * OmniPHR standard the component is not triggered by the middleware;
     * the passthrough here only materializes the archetype.
     */
    public Archetype toOmniPhrStandard(RawRecord raw) {
        switch (raw.getStandard()) {
            case OPENEHR:
                return Archetype.fromCanonicalString(raw.getContent());
            case HL7:
                return translateHl7(raw);
            case PROPRIETARY:
                return translateProprietary(raw);
            default:
                throw new IllegalArgumentException("unsupported standard " + raw.getStandard());
        }
    }

    /**
     * Output gateway: converts an archetype back to a foreign standard for
     * a consumer organization that does not use openEHR.
     */
    public String fromOmniPhrStandard(Archetype archetype, StandardFormat target) {
        if (target == StandardFormat.OPENEHR) {
            return archetype.toCanonicalString();
        }
        StringBuilder sb = new StringBuilder();
        if (target == StandardFormat.HL7) {
            sb.append("MSH|^~\\&|OMNIPHR|").append(archetype.getCategory()).append('\n');
            int i = 1;
            for (Map.Entry<String, String> e : archetype.getItems().entrySet()) {
                sb.append("OBX|").append(i++).append("|ST|").append(e.getKey())
                        .append("|").append(e.getValue()).append('\n');
            }
        } else {
            sb.append("# ").append(archetype.getArchetypeId()).append('\n');
            for (Map.Entry<String, String> e : archetype.getItems().entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // (1) Open standard different from the adopted one: HL7 v2-like
    // ------------------------------------------------------------------

    /**
     * Parses a simplified HL7 v2 ORU-like message. Example segment:
     * {@code OBX|1|NM|718-7^Hemoglobin^LN|13.5|g/dL}
     * The LOINC code (718-7) is resolved through the ontology to the
     * openEHR archetype and archetype path, leveraging the connection of
     * openEHR with other health data standards such as HL7 and LOINC.
     */
    private Archetype translateHl7(RawRecord raw) {
        String archetypeId = null;
        HealthCategory category = raw.getCategoryHint();
        Map<String, String> items = new LinkedHashMap<>();
        for (String line : raw.getContent().split("\n")) {
            String[] f = line.trim().split("\\|");
            if (f.length >= 5 && f[0].equals("OBX")) {
                String[] code = f[3].split("\\^");
                String loinc = "LOINC:" + code[0];
                String mappedArchetype = semanticRepository.lookup(loinc, SemanticRepository.MAPS_TO_ARCHETYPE);
                String mappedPath = semanticRepository.lookup(loinc, SemanticRepository.MAPS_TO_PATH);
                if (mappedArchetype != null) {
                    archetypeId = mappedArchetype;
                }
                String label = mappedPath != null ? mappedPath
                        : (code.length > 1 ? code[1].toLowerCase() : code[0]);
                String value = f[4] + (f.length > 5 && !f[5].isEmpty() ? " " + f[5] : "");
                items.put(label, value);
            } else if (f.length >= 4 && f[0].equals("PID")) {
                items.put("subject", f[3]);
            }
        }
        if (archetypeId == null) {
            archetypeId = "openEHR-EHR-OBSERVATION.laboratory_test_result.v1";
        }
        if (category == null) {
            category = HealthCategory.LAB_RESULT;
        }
        return new Archetype(archetypeId, category, items);
    }

    // ------------------------------------------------------------------
    // (2) Proprietary standard: free text + NLP + ontology
    // ------------------------------------------------------------------

    private static final Pattern KEY_VALUE = Pattern.compile(
            "([\\p{L} _-]+)\\s*[:=]\\s*([^\\n;]+)");

    /**
     * Very small NLP routine: extracts key/value pairs from legacy free-text
     * records and normalizes the terms through the ontology synonyms stored
     * in the semantic database, enabling knowledge extraction from legacy
     * health records.
     */
    private Archetype translateProprietary(RawRecord raw) {
        Map<String, String> items = new LinkedHashMap<>();
        java.util.List<String> candidateArchetypes = new java.util.ArrayList<>();
        Matcher m = KEY_VALUE.matcher(raw.getContent());
        while (m.find()) {
            String term = m.group(1).trim();
            String value = m.group(2).trim();
            String concept = semanticRepository.resolveSynonym(term);
            if (concept != null) {
                String mappedArchetype = semanticRepository.lookup(concept, SemanticRepository.MAPS_TO_ARCHETYPE);
                String mappedPath = semanticRepository.lookup(concept, SemanticRepository.MAPS_TO_PATH);
                if (mappedArchetype != null) {
                    candidateArchetypes.add(mappedArchetype);
                }
                items.put(mappedPath != null ? mappedPath : SemanticRepository.normalize(term), value);
            } else {
                items.put(SemanticRepository.normalize(term), value);
            }
        }
        HealthCategory category = raw.getCategoryHint() != null ? raw.getCategoryHint() : HealthCategory.DIAGNOSIS;
        // the equivalent ontology is per datablock: prefer the candidate
        // archetype coherent with the record's logical division
        String archetypeId = null;
        for (String candidate : candidateArchetypes) {
            if (matchesCategory(candidate, category)) {
                archetypeId = candidate;
                break;
            }
        }
        if (archetypeId == null && !candidateArchetypes.isEmpty()) {
            archetypeId = candidateArchetypes.get(0);
        }
        if (archetypeId == null) {
            archetypeId = "openEHR-EHR-EVALUATION.problem_diagnosis.v1";
        }
        return new Archetype(archetypeId, category, items);
    }

    private static boolean matchesCategory(String archetypeId, HealthCategory category) {
        switch (category) {
            case DIAGNOSIS:
                return archetypeId.contains("problem_diagnosis");
            case LAB_RESULT:
                return archetypeId.contains("laboratory");
            case MEDICATION:
                return archetypeId.contains("medication");
            case VITAL_SIGNS:
                return archetypeId.contains("blood_pressure") || archetypeId.contains("pulse");
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Ontology seed: an equivalent ontology for each datablock, stored in
    // the semantic database
    // ------------------------------------------------------------------

    private void seedOntology() {
        // LOINC-coded laboratory observations (HL7 path)
        semanticRepository.add("LOINC:718-7", SemanticRepository.MAPS_TO_ARCHETYPE,
                "openEHR-EHR-OBSERVATION.laboratory_test_result.v1");
        semanticRepository.add("LOINC:718-7", SemanticRepository.MAPS_TO_PATH, "hemoglobin");
        semanticRepository.add("LOINC:2345-7", SemanticRepository.MAPS_TO_ARCHETYPE,
                "openEHR-EHR-OBSERVATION.laboratory_test_result.v1");
        semanticRepository.add("LOINC:2345-7", SemanticRepository.MAPS_TO_PATH, "glucose");

        // Proprietary/legacy terms (NLP path); synonyms for other languages
        // can be registered through the administrator functions
        semanticRepository.add("blood pressure", SemanticRepository.SYNONYM_OF, "concept:blood_pressure");
        semanticRepository.add("arterial pressure", SemanticRepository.SYNONYM_OF, "concept:blood_pressure");
        semanticRepository.add("concept:blood_pressure", SemanticRepository.MAPS_TO_ARCHETYPE,
                "openEHR-EHR-OBSERVATION.blood_pressure.v1");
        semanticRepository.add("concept:blood_pressure", SemanticRepository.MAPS_TO_PATH, "blood_pressure");

        semanticRepository.add("diagnosis", SemanticRepository.SYNONYM_OF, "concept:diagnosis");
        semanticRepository.add("clinical impression", SemanticRepository.SYNONYM_OF, "concept:diagnosis");
        semanticRepository.add("concept:diagnosis", SemanticRepository.MAPS_TO_ARCHETYPE,
                "openEHR-EHR-EVALUATION.problem_diagnosis.v1");
        semanticRepository.add("concept:diagnosis", SemanticRepository.MAPS_TO_PATH, "problem_diagnosis");

        semanticRepository.add("medication", SemanticRepository.SYNONYM_OF, "concept:medication");
        semanticRepository.add("prescribed drug", SemanticRepository.SYNONYM_OF, "concept:medication");
        semanticRepository.add("concept:medication", SemanticRepository.MAPS_TO_ARCHETYPE,
                "openEHR-EHR-INSTRUCTION.medication_order.v1");
        semanticRepository.add("concept:medication", SemanticRepository.MAPS_TO_PATH, "medication_item");
    }
}
