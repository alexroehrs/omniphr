package br.unisinos.omniphr.middleware.datablock;

import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.core.StandardFormat;

/**
 * A health record as produced at the source (healthcare provider system,
 * patient device or sensor), before entering the model through the
 * Translator, the input and output gateway of the datablocks.
 */
public final class RawRecord {

    private final StandardFormat standard;
    private final String content;
    private final HealthCategory categoryHint; // may be null for legacy records

    public RawRecord(StandardFormat standard, String content, HealthCategory categoryHint) {
        this.standard = standard;
        this.content = content;
        this.categoryHint = categoryHint;
    }

    public StandardFormat getStandard() {
        return standard;
    }

    public String getContent() {
        return content;
    }

    public HealthCategory getCategoryHint() {
        return categoryHint;
    }
}
