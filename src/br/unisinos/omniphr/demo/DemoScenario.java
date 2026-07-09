package br.unisinos.omniphr.demo;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.core.Page;
import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.core.openehr.Archetype;
import br.unisinos.omniphr.middleware.datablock.NodesManager;
import br.unisinos.omniphr.middleware.datablock.RawRecord;
import br.unisinos.omniphr.middleware.security.Encryptor;
import br.unisinos.omniphr.middleware.security.RolesAndPrivileges;
import br.unisinos.omniphr.net.NetworkEnvironment;
import br.unisinos.omniphr.node.Actor;
import br.unisinos.omniphr.node.RegularNode;
import br.unisinos.omniphr.overlay.RoutingOverlay;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.chord.Hops;
import br.unisinos.omniphr.p2p.pubsub.PubSubService;
import br.unisinos.omniphr.repository.UserAccount;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end demonstration of the OmniPHR model, following a typical usage
 * scenario: the patient goes to various health organizations and is assisted
 * by different health professionals, so the health records are updated many
 * times from heterogeneous sources.
 *
 * The network holds twelve nodes grouped into four subnetworks, each
 * subnetwork served by a routing overlay, with heterogeneous devices acting
 * as providers and consumers of PHR data.
 *
 * All demonstration data is synthetic and fully non-identifiable (no real
 * names, birth dates, documents, addresses or organization names), in line
 * with data protection regulations such as LGPD and GDPR.
 */
public class DemoScenario {

    private static String rule(char c, int width) {
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(rule('=', 78));
        System.out.println("  " + title);
        System.out.println(rule('=', 78));
    }

    public void run() throws Exception {
        section("OmniPHR reference implementation - demo scenario");

        // ------------------------------------------------------------------
        // Network bootstrap: 12 nodes in 4 subnetworks
        // ------------------------------------------------------------------
        NetworkEnvironment network = new NetworkEnvironment(42);
        network.setSubnetsPerBackboneDomain(4); // single backbone router domain

        RoutingOverlay ro1 = new RoutingOverlay("overlay-hospital", network, 0);
        RoutingOverlay ro2 = new RoutingOverlay("overlay-lab", network, 1);
        RoutingOverlay ro3 = new RoutingOverlay("overlay-patient", network, 2);
        RoutingOverlay ro4 = new RoutingOverlay("overlay-firstaid", network, 3);

        RegularNode hospital = new RegularNode("hospital-a-ehr", network, 0,
                RegularNode.Kind.HOSPITAL_EHR, StandardFormat.OPENEHR);
        RegularNode hospitalBackup = new RegularNode("hospital-a-mirror", network, 0,
                RegularNode.Kind.HOSPITAL_EHR, StandardFormat.OPENEHR);
        RegularNode lab = new RegularNode("laboratory-b-lis", network, 1,
                RegularNode.Kind.LABORATORY, StandardFormat.HL7);
        RegularNode clinic = new RegularNode("clinic-c-emr", network, 1,
                RegularNode.Kind.CLINIC_EMR, StandardFormat.PROPRIETARY);
        RegularNode smartphone = new RegularNode("patient-device-1", network, 2,
                RegularNode.Kind.PATIENT_DEVICE, StandardFormat.OPENEHR);
        RegularNode wearable = new RegularNode("wearable-sensor-1", network, 2,
                RegularNode.Kind.WEARABLE_SENSOR, StandardFormat.OPENEHR);
        RegularNode firstAid = new RegularNode("first-aid-station-1", network, 3,
                RegularNode.Kind.FIRST_AID_STATION, StandardFormat.OPENEHR);
        RegularNode firstAidTablet = new RegularNode("first-aid-tablet", network, 3,
                RegularNode.Kind.PATIENT_DEVICE, StandardFormat.OPENEHR);

        // the Nodes Manager controls the input of nodes
        NodesManager nodesManager = ro1.getMiddleware().getNodesManager();
        nodesManager.join(ro1, null);
        for (ChordNode n : new ChordNode[]{ro2, ro3, ro4, hospital, hospitalBackup, lab,
                clinic, smartphone, wearable, firstAid, firstAidTablet}) {
            nodesManager.join(n, ro1);
        }
        for (ChordNode n : network.allNodes()) {
            n.fixAllFingers();
        }

        // Chord maintenance running on a background thread
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chord-maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleAtFixedRate(nodesManager::stabilizationRound, 50, 50, TimeUnit.MILLISECONDS);

        System.out.println("Network up: 12 nodes, 4 subnetworks, 4 routing overlays.");
        System.out.println(nodesManager.loadReport());

        // the hospital subscribes to updates of its patients
        PubSubService.Subscriber hospitalSystem = new PubSubService.Subscriber() {
            @Override
            public String subscriberId() {
                return hospital.getName();
            }

            @Override
            public void onMessage(String topic, br.unisinos.omniphr.p2p.pubsub.PubSubMessage m) {
                System.out.println("      [pub-sub -> " + hospital.getName() + "] " + m);
            }
        };

        // ------------------------------------------------------------------
        // Users: registration with OpenID-style unique id
        // ------------------------------------------------------------------
        section("User registration - authenticator and key pairs");
        Encryptor keyFactory = ro1.getMiddleware().getEncryptor();

        // fully synthetic, non-identifiable personas (LGPD/GDPR)
        Actor patient = new Actor("SYNTH-NAME-001", AuthorRole.PATIENT, keyFactory.generateKeyPair());
        Actor physician = new Actor("SYNTH-NAME-002", AuthorRole.HEALTH_PROFESSIONAL, keyFactory.generateKeyPair());
        Actor nurse = new Actor("SYNTH-NAME-003", AuthorRole.HEALTH_PROFESSIONAL, keyFactory.generateKeyPair());
        Actor labTech = new Actor("SYNTH-NAME-004", AuthorRole.HEALTH_PROFESSIONAL, keyFactory.generateKeyPair());
        Actor clinicPhysician = new Actor("SYNTH-NAME-005", AuthorRole.HEALTH_PROFESSIONAL, keyFactory.generateKeyPair());
        Actor thirdParty = new Actor("SYNTH-NAME-006", AuthorRole.AUTHORIZED_THIRD_PARTY, keyFactory.generateKeyPair());
        Actor sensor = new Actor("SENSOR-SYNTH-001", AuthorRole.SENSOR, keyFactory.generateKeyPair());

        UserAccount patientAcc = ro1.registerUser(patient, "DOB-SYNTH-001", "DOC-SYNTH-001", "recovery-code-p001");
        ro1.registerUser(physician, "DOB-SYNTH-002", "LIC-SYNTH-002", "recovery-code-p002");
        ro1.registerUser(nurse, "DOB-SYNTH-003", "LIC-SYNTH-003", "recovery-code-p003");
        ro1.registerUser(labTech, "DOB-SYNTH-004", "LIC-SYNTH-004", "recovery-code-p004");
        ro1.registerUser(clinicPhysician, "DOB-SYNTH-005", "LIC-SYNTH-005", "recovery-code-p005");
        ro1.registerUser(thirdParty, "DOB-SYNTH-006", "DOC-SYNTH-006", "recovery-code-p006");
        ro1.registerUser(sensor, "MFG-SYNTH-001", "SN-SYNTH-001", "recovery-code-s001");
        System.out.println("Patient id (single hash code): " + patientAcc.getOpenId());

        // duplicate registration attempt resolves to the same identifier
        UserAccount duplicated = ro1.registerUser(
                new Actor("SYNTH-NAME-001", AuthorRole.PATIENT, patient.getPublicKey() == null ? null : keyFactory.generateKeyPair()),
                "DOB-SYNTH-001", "DOC-SYNTH-001", "other");
        System.out.println("Duplicate registration avoided: same id -> "
                + duplicated.getOpenId().equals(patientAcc.getOpenId()));

        String patientId = patient.getOpenId();
        ro1.getPubSub().subscribe(RoutingOverlay.patientTopic(patientId), hospitalSystem);

        // challenge-response authentication
        String nonce = ro1.getMiddleware().getAuthenticator().requestChallenge(patientId);
        String token = ro1.getMiddleware().getAuthenticator().authenticate(patientId, patient.sign(nonce));
        System.out.println("Patient authenticated by challenge-response, session " + token.substring(0, 8) + "...");

        // ------------------------------------------------------------------
        // Administrator functions: profiles, archetypes, standards
        // ------------------------------------------------------------------
        section("Administrator functions of the routing overlay");
        for (String archetype : new String[]{
                "openEHR-EHR-EVALUATION.demographics.v1",
                "openEHR-EHR-EVALUATION.problem_diagnosis.v1",
                "openEHR-EHR-INSTRUCTION.medication_order.v1",
                "openEHR-EHR-OBSERVATION.laboratory_test_result.v1",
                "openEHR-EHR-OBSERVATION.blood_pressure.v1",
                "openEHR-EHR-OBSERVATION.pulse.v1",
                "openEHR-EHR-OBSERVATION.body_weight.v1"}) {
            ro1.adminRegisterStandardArchetype(archetype);
        }
        System.out.println("Datablock types of the standard registered: "
                + ro1.getMiddleware().getRelationalRepository().getRegisteredArchetypes().size());

        ro1.adminDefineProfileType("org-hospital-a", "PHYSICIAN", EnumSet.of(
                HealthCategory.DEMOGRAPHICS, HealthCategory.DIAGNOSIS, HealthCategory.MEDICATION,
                HealthCategory.LAB_RESULT, HealthCategory.VITAL_SIGNS, HealthCategory.IMAGING));
        ro1.adminDefineProfileType("org-hospital-a", "NURSE", EnumSet.of(HealthCategory.VITAL_SIGNS));
        ro1.adminDefineProfileType("org-laboratory-b", "LAB_TECH", EnumSet.of(HealthCategory.LAB_RESULT));
        RolesAndPrivileges roles = ro1.getMiddleware().getRolesAndPrivileges();
        roles.assignProfile(physician.getOpenId(), "org-hospital-a", "PHYSICIAN");
        roles.assignProfile(nurse.getOpenId(), "org-hospital-a", "NURSE");
        roles.assignProfile(labTech.getOpenId(), "org-laboratory-b", "LAB_TECH");
        System.out.println("Organizational profiles defined and assigned.");

        // ------------------------------------------------------------------
        // Genesis datablock: demographics, under the patient's responsibility
        // ------------------------------------------------------------------
        section("Datablock 0 - demographics informed by the patient");
        Map<String, String> demo = new LinkedHashMap<>();
        demo.put("full_name", "SYNTH-NAME-001");
        demo.put("birth_date", "DOB-SYNTH-001");
        demo.put("gender", "not-specified");
        demo.put("current_address", "SYNTH-ADDRESS-001");
        demo.put("identification_document", "DOC-SYNTH-001");
        Datablock genesis = ro1.submitDatablock(patient, patientId,
                new RawRecord(StandardFormat.OPENEHR,
                        new Archetype("openEHR-EHR-EVALUATION.demographics.v1",
                                HealthCategory.DEMOGRAPHICS, demo).toCanonicalString(),
                        HealthCategory.DEMOGRAPHICS),
                smartphone);
        System.out.println("  " + genesis);

        // ------------------------------------------------------------------
        // Patient grants access - personal approach
        // ------------------------------------------------------------------
        section("Access permissions - the patient is the master controller");
        ro1.grantAccess(patient, physician.getOpenId(), EnumSet.of(HealthCategory.DIAGNOSIS,
                HealthCategory.MEDICATION, HealthCategory.LAB_RESULT, HealthCategory.VITAL_SIGNS));
        ro1.grantAccess(patient, nurse.getOpenId(), EnumSet.of(HealthCategory.VITAL_SIGNS,
                HealthCategory.DIAGNOSIS)); // nurse profile will restrict to VITAL_SIGNS
        ro1.grantAccess(patient, labTech.getOpenId(), EnumSet.of(HealthCategory.LAB_RESULT));
        ro1.grantAccess(patient, clinicPhysician.getOpenId(), EnumSet.of(HealthCategory.DIAGNOSIS,
                HealthCategory.VITAL_SIGNS));
        System.out.println("Grants: physician (4 categories), nurse (2), lab technician (1), clinic physician (2).");

        // ------------------------------------------------------------------
        // Hospital native openEHR: diagnosis and prescription
        // ------------------------------------------------------------------
        section("Hospital (openEHR native) - Translator NOT triggered");
        Map<String, String> diag = new LinkedHashMap<>();
        diag.put("problem_diagnosis", "Asthma (ICD-10 J45)");
        diag.put("severity", "moderate");
        diag.put("clinical_description", "Recurrent wheezing and dyspnea on exertion");
        Datablock diagnosis = ro1.submitDatablock(physician, patientId,
                new RawRecord(StandardFormat.OPENEHR,
                        new Archetype("openEHR-EHR-EVALUATION.problem_diagnosis.v1",
                                HealthCategory.DIAGNOSIS, diag).toCanonicalString(),
                        HealthCategory.DIAGNOSIS),
                hospital);
        System.out.println("  " + diagnosis);

        Map<String, String> med = new LinkedHashMap<>();
        med.put("medication_item", "Salbutamol 100 mcg inhaler");
        med.put("dosage", "2 puffs when needed, max 8/day");
        Datablock prescription = ro1.submitDatablock(physician, patientId,
                new RawRecord(StandardFormat.OPENEHR,
                        new Archetype("openEHR-EHR-INSTRUCTION.medication_order.v1",
                                HealthCategory.MEDICATION, med).toCanonicalString(),
                        HealthCategory.MEDICATION),
                hospital);
        System.out.println("  " + prescription);
        System.out.println("Originals kept at the health organization: "
                + hospital.getName() + " holds " + hospital.originalsCount() + " originals.");

        // ------------------------------------------------------------------
        // Laboratory sends HL7: Translator path (1) - open standard != adopted
        // ------------------------------------------------------------------
        section("Laboratory (HL7) - Translator triggered, ontology + LOINC");
        String hl7 = "MSH|^~\\&|LIS|LAB-B\n"
                + "PID|1||" + patientId + "\n"
                + "OBX|1|NM|718-7^Hemoglobin^LN|13.5|g/dL\n"
                + "OBX|2|NM|2345-7^Glucose^LN|92|mg/dL";
        Datablock labResult = ro1.submitDatablock(labTech, patientId,
                new RawRecord(StandardFormat.HL7, hl7, HealthCategory.LAB_RESULT), lab);
        System.out.println("  " + labResult);
        System.out.println("  translated content readable by the patient:");
        Archetype labArchetype = ro1.readContent(labResult, patient);
        System.out.println("    " + labArchetype);

        // ------------------------------------------------------------------
        // Clinic legacy free-text: Translator path (2) - proprietary + NLP
        // ------------------------------------------------------------------
        section("Clinic (proprietary/legacy) - Translator with NLP + semantic DB");
        String legacy = "Patient seen in routine visit.\n"
                + "Blood Pressure: 120/80 mmHg\n"
                + "Diagnosis: mild allergic rhinitis";
        Datablock clinicNote = ro1.submitDatablock(clinicPhysician, patientId,
                new RawRecord(StandardFormat.PROPRIETARY, legacy, HealthCategory.DIAGNOSIS), clinic);
        System.out.println("  " + clinicNote);
        System.out.println("  extracted by NLP/ontology: " + ro1.readContent(clinicNote, patient).getItems());

        // ------------------------------------------------------------------
        // Sensor stream: sensor datablocks are properly identified
        // ------------------------------------------------------------------
        section("Wearable sensor - datablocks signed by the device");
        for (String bpm : new String[]{"71", "84"}) {
            Map<String, String> hr = new LinkedHashMap<>();
            hr.put("pulse_rate", bpm + " bpm");
            hr.put("position", "sitting");
            Datablock hrBlock = ro1.submitDatablock(sensor, patientId,
                    new RawRecord(StandardFormat.OPENEHR,
                            new Archetype("openEHR-EHR-OBSERVATION.pulse.v1",
                                    HealthCategory.VITAL_SIGNS, hr).toCanonicalString(),
                            HealthCategory.VITAL_SIGNS),
                    wearable);
            System.out.println("  " + hrBlock);
            Thread.sleep(5);
        }

        // patient-informed wellness data, an advantage of PHR over EHR
        Map<String, String> weight = new LinkedHashMap<>();
        weight.put("body_weight", "64.2 kg");
        Datablock weightBlock = ro1.submitDatablock(patient, patientId,
                new RawRecord(StandardFormat.OPENEHR,
                        new Archetype("openEHR-EHR-OBSERVATION.body_weight.v1",
                                HealthCategory.WELLNESS, weight).toCanonicalString(),
                        HealthCategory.WELLNESS),
                smartphone);
        System.out.println("  " + weightBlock + " (patient-informed, stored on routing overlay)");

        // ------------------------------------------------------------------
        // Unified viewpoint with pagination
        // ------------------------------------------------------------------
        section("Unified PHR viewpoint, paginated, most recent first");
        Page page1 = ro1.queryPhr(patientId, patientId, null, 1, 4);
        System.out.println("Patient - " + page1);
        for (Datablock b : page1.getItems()) {
            System.out.println("  " + b);
        }
        Page page2 = ro1.queryPhr(patientId, patientId, null, 2, 4);
        System.out.println("Patient - " + page2);
        for (Datablock b : page2.getItems()) {
            System.out.println("  " + b);
        }
        System.out.println("Chain validated as intact during assembly.");

        // ------------------------------------------------------------------
        // Privilege-filtered views
        // ------------------------------------------------------------------
        section("Views filtered by roles and privileges");
        Page physicianView = ro1.queryPhr(physician.getOpenId(), patientId, null, 1, 10);
        System.out.println("Physician (PHYSICIAN profile + personal grant): " + physicianView.getItems().size()
                + " blocks visible of " + page1.getTotalBlocks());
        Page nurseView = ro1.queryPhr(nurse.getOpenId(), patientId, null, 1, 10);
        System.out.println("Nurse (NURSE profile restricts to VITAL_SIGNS): " + nurseView.getItems().size()
                + " blocks visible -> " + nurseView.getItems());
        Page thirdPartyView = ro1.queryPhr(thirdParty.getOpenId(), patientId, null, 1, 10);
        System.out.println("Third party (no grant): " + thirdPartyView.getItems().size()
                + " blocks visible (denied by default)");

        System.out.println();
        System.out.println("The physician decrypts the lab result re-wrapped on grant:");
        System.out.println("  " + ro1.readContent(labResult, physician).getItems());
        try {
            ro1.readContent(labResult, thirdParty);
        } catch (SecurityException e) {
            System.out.println("The third party cannot decrypt: " + e.getMessage());
        }

        // Output gateway: consumer organization using another standard
        System.out.println();
        System.out.println("Output gateway - lab result translated back to HL7 for a consumer:");
        for (String line : ro1.getMiddleware().getTranslator()
                .fromOmniPhrStandard(labArchetype, StandardFormat.HL7).split("\n")) {
            System.out.println("    " + line);
        }

        // ------------------------------------------------------------------
        // Revocation at any time
        // ------------------------------------------------------------------
        section("Revocation - the patient revokes at any time");
        ro1.revokeAccess(patient, physician.getOpenId());
        Page physicianAfter = ro1.queryPhr(physician.getOpenId(), patientId, null, 1, 10);
        System.out.println("Physician after revocation: " + physicianAfter.getItems().size() + " blocks visible");
        try {
            ro1.readContent(labResult, physician);
        } catch (SecurityException e) {
            System.out.println("The physician cannot decrypt anymore: content key removed.");
        }

        // ------------------------------------------------------------------
        // Tampering attempt detected by the Validator
        // ------------------------------------------------------------------
        section("Manipulation attempt - Validator detects broken chaining");
        Datablock forged = forgeBlock(prescription, thirdParty);
        for (ChordNode n : network.allNodes()) {
            if (n.getBlock(prescription.getBlockId()) != null) {
                n.storeBlock(forged); // attacker overwrites every replica found
            }
        }
        ro1.getMiddleware().getMessageRouter().clearCache();
        try {
            ro1.queryPhr(patientId, patientId, null, 1, 10);
            System.out.println("!! manipulation NOT detected (unexpected)");
        } catch (SecurityException e) {
            System.out.println("Manipulation detected on PHR assembly: " + e.getMessage());
        }
        // restore honest replicas
        for (ChordNode n : network.allNodes()) {
            if (n.getBlock(prescription.getBlockId()) != null) {
                n.storeBlock(prescription);
            }
        }
        ro1.getMiddleware().getMessageRouter().clearCache();
        System.out.println("Honest replicas restored; chain valid again: "
                + (ro1.queryPhr(patientId, patientId, null, 1, 1).getTotalBlocks() + " blocks."));

        // ------------------------------------------------------------------
        // Node failure: replicas keep the PHR available
        // ------------------------------------------------------------------
        section("Node failure - availability through replication");
        System.out.println("Abrupt failure of node " + lab.getName() + "...");
        lab.fail();
        network.unregister(lab);
        ro1.getMiddleware().getNodesManager().stabilizationRound();
        ro1.getMiddleware().getMessageRouter().clearCache();
        Datablock recovered = ro1.getMiddleware().getDistributor().fetch(labResult.getBlockId(), new Hops());
        System.out.println("Lab result still retrievable from replicas: " + (recovered != null));

        // graceful leave with replica re-dissemination
        ro1.getMiddleware().getNodesManager().leave(firstAidTablet, ro1.getMiddleware().getDistributor());
        System.out.println("Graceful leave of " + firstAidTablet.getName() + " with copies re-disseminated.");

        // ------------------------------------------------------------------
        // Blocked account and recovery
        // ------------------------------------------------------------------
        section("Unauthorized access attempts - blocking and recovery");
        for (int i = 0; i < 3; i++) {
            try {
                String c = ro1.getMiddleware().getAuthenticator().requestChallenge(patientId);
                ro1.getMiddleware().getAuthenticator().authenticate(patientId, thirdParty.sign(c));
            } catch (SecurityException e) {
                System.out.println("  attempt " + (i + 1) + ": " + e.getMessage());
            }
        }
        boolean recovered2 = ro1.getMiddleware().getAuthenticator().recoverAccess(patientId, "recovery-code-p001");
        System.out.println("Patient recovers access with recovery code: " + recovered2);

        // ------------------------------------------------------------------
        // Summary
        // ------------------------------------------------------------------
        section("Network summary");
        System.out.println(ro1.getMiddleware().getNodesManager().loadReport());
        System.out.println(ro1.getMiddleware().getMessageRouter().cacheStats());
        System.out.printf("Messages routed: %d | avg one-way hop count: %.2f | avg one-way latency: %.3f s%n",
                network.getMetrics().getMessageCount(),
                network.getMetrics().averageHopCount(),
                network.getMetrics().averageLatencySeconds());
        System.out.println();
        System.out.println("Audit trail (last entries):");
        List<String> audit = roles.getAuditLog();
        for (int i = Math.max(0, audit.size() - 5); i < audit.size(); i++) {
            System.out.println("  " + audit.get(i));
        }
        maintenance.shutdownNow();
        System.out.println();
        System.out.println("Demo finished.");
    }

    /** Builds a forged copy of a block with altered content (attacker has no valid key). */
    private Datablock forgeBlock(Datablock original, Actor attacker) {
        return new Datablock(original.getBlockId(), original.getPatientId(), original.getSequence(),
                original.getCreatedAt(), original.getPreviousHash(), original.getArchetypeId(),
                original.getCategory(), original.getSourceStandard(), original.getAuthorId(),
                original.getAuthorRole(), original.getEncryptedContent(),
                original.getEncryptedContentKeys(),
                "eeee" + original.getContentHash().substring(4), // tampered content hash
                attacker.sign("forged"));                        // invalid signature
    }
}
