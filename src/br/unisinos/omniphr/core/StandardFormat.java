package br.unisinos.omniphr.core;

/**
 * Health record standards handled by the model. openEHR is the open standard
 * adopted by OmniPHR by default; the Translator component is triggered only
 * when the healthcare provider uses a different standard, whether open or
 * proprietary.
 */
public enum StandardFormat {
    /** Default open standard adopted by OmniPHR. */
    OPENEHR,
    /** Example of a different open standard (HL7 family). */
    HL7,
    /** Legacy/proprietary format kept by a health organization. */
    PROPRIETARY
}
