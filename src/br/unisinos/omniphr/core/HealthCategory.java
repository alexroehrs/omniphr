package br.unisinos.omniphr.core;

/**
 * Logical division of the patient's health datasets, used to organize the
 * PHR hierarchically: laboratory data, drug-related data, imaging and so on.
 */
public enum HealthCategory {
    DEMOGRAPHICS,   // informed under the patient's responsibility
    DIAGNOSIS,      // informed under the healthcare provider's responsibility
    LAB_RESULT,
    MEDICATION,
    IMAGING,        // e.g. X-ray datasets
    VITAL_SIGNS,    // e.g. data from monitoring sensors
    WELLNESS        // e.g. sports activities, eating habits
}
