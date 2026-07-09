package br.unisinos.omniphr.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic repository of the routing overlay. It stores an equivalent
 * ontology for each datablock, used together with NLP to automate the
 * conversion of legacy health records to the standard format adopted by the
 * model.
 *
 * The ontology is represented as subject-predicate-object triples that map
 * fields of foreign standards (e.g. HL7/LOINC codes, or free-text terms of
 * proprietary records) to openEHR archetypes and archetype paths.
 */
public class SemanticRepository {

    /** Ontology triple. */
    public static final class Triple {
        public final String subject;
        public final String predicate;
        public final String object;

        public Triple(String subject, String predicate, String object) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
        }

        @Override
        public String toString() {
            return "(" + subject + ", " + predicate + ", " + object + ")";
        }
    }

    public static final String MAPS_TO_ARCHETYPE = "omniphr:mapsToArchetype";
    public static final String MAPS_TO_PATH = "omniphr:mapsToPath";
    public static final String SYNONYM_OF = "omniphr:synonymOf";

    private final List<Triple> triples = new ArrayList<>();
    private final Map<String, String> synonymCache = new ConcurrentHashMap<>();

    public synchronized void add(String subject, String predicate, String object) {
        triples.add(new Triple(subject, predicate, object));
        if (SYNONYM_OF.equals(predicate)) {
            synonymCache.put(normalize(subject), object);
        }
    }

    public synchronized List<Triple> query(String subject, String predicate) {
        List<Triple> out = new ArrayList<>();
        for (Triple t : triples) {
            if ((subject == null || t.subject.equalsIgnoreCase(subject))
                    && (predicate == null || t.predicate.equals(predicate))) {
                out.add(t);
            }
        }
        return out;
    }

    /** First object for (subject, predicate), or null. */
    public String lookup(String subject, String predicate) {
        List<Triple> found = query(subject, predicate);
        return found.isEmpty() ? null : found.get(0).object;
    }

    /** Resolves a free-text term to its canonical concept, if known. */
    public String resolveSynonym(String term) {
        return synonymCache.get(normalize(term));
    }

    public static String normalize(String term) {
        return term.toLowerCase(Locale.ROOT).trim()
                .replace("á", "a").replace("ã", "a").replace("â", "a")
                .replace("é", "e").replace("ê", "e")
                .replace("í", "i")
                .replace("ó", "o").replace("õ", "o").replace("ô", "o")
                .replace("ú", "u").replace("ç", "c");
    }

    public synchronized int size() {
        return triples.size();
    }
}
