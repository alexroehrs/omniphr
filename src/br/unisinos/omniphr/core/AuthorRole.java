package br.unisinos.omniphr.core;

/**
 * Role of the informant responsible for a datablock.
 *
 * Every datablock is encrypted and digitally signed by the party responsible
 * for inserting the information: a health professional, the patient, someone
 * the patient authorized, or a sensor device reporting on the patient's
 * behalf.
 */
public enum AuthorRole {
    PATIENT,
    HEALTH_PROFESSIONAL,
    AUTHORIZED_THIRD_PARTY,
    SENSOR
}
