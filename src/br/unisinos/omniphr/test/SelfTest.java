package br.unisinos.omniphr.test;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.core.Page;
import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.core.openehr.Archetype;
import br.unisinos.omniphr.middleware.datablock.NodesManager;
import br.unisinos.omniphr.middleware.datablock.RawRecord;
import br.unisinos.omniphr.middleware.security.Authenticator;
import br.unisinos.omniphr.middleware.security.Encryptor;
import br.unisinos.omniphr.net.SimulatedNetwork;
import br.unisinos.omniphr.node.Actor;
import br.unisinos.omniphr.node.RegularNode;
import br.unisinos.omniphr.overlay.RoutingOverlay;
import br.unisinos.omniphr.p2p.chord.ChordId;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.chord.Hops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Self-verification of the reference implementation: checks each mechanism
 * of the model (chaining, signature, encryption, Chord lookup, translation,
 * privileges and pagination).
 */
public class SelfTest {

    private int passed = 0, failed = 0;

    private void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }

    public int run() throws Exception {
        System.out.println("OmniPHR self-test");
        System.out.println();

        // ------------------------------------------------------------
        // Chord lookup correctness against brute force
        // ------------------------------------------------------------
        SimulatedNetwork net = new SimulatedNetwork(7);
        List<ChordNode> ring = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            ChordNode n = new ChordNode("test-node-" + i, net, i % 4);
            net.register(n);
            ring.add(n);
        }
        net.convergeRing();
        ring.sort(Comparator.comparing(ChordNode::getId));
        Random rnd = new Random(11);
        boolean allCorrect = true;
        double totalHops = 0;
        int lookups = 300;
        for (int i = 0; i < lookups; i++) {
            ChordId key = ChordId.of("key-" + rnd.nextLong());
            ChordNode expected = bruteForceSuccessor(ring, key);
            Hops hops = new Hops();
            ChordNode found = ring.get(rnd.nextInt(ring.size())).findSuccessor(key, hops);
            if (found != expected) {
                allCorrect = false;
            }
            totalHops += hops.getCount();
        }
        check("Chord findSuccessor equals brute-force owner (300 keys, 64 nodes)", allCorrect);
        double avgHops = totalHops / lookups;
        check(String.format("Chord lookup is O(log N): avg %.2f hops <= log2(64)=6", avgHops), avgHops <= 6.0);

        // ------------------------------------------------------------
        // Full pipeline over a small overlay network
        // ------------------------------------------------------------
        SimulatedNetwork net2 = new SimulatedNetwork(21);
        RoutingOverlay overlay = new RoutingOverlay("t-overlay", net2, 0);
        RegularNode provider = new RegularNode("t-hospital", net2, 0,
                RegularNode.Kind.HOSPITAL_EHR, StandardFormat.OPENEHR);
        RegularNode device = new RegularNode("t-phone", net2, 1,
                RegularNode.Kind.PATIENT_DEVICE, StandardFormat.OPENEHR);
        NodesManager nm = overlay.getMiddleware().getNodesManager();
        nm.join(overlay, null);
        nm.join(provider, overlay);
        nm.join(device, overlay);
        for (ChordNode n : net2.allNodes()) {
            n.fixAllFingers();
        }

        Encryptor enc = overlay.getMiddleware().getEncryptor();
        Actor patient = new Actor("SYNTH-PATIENT-T1", AuthorRole.PATIENT, enc.generateKeyPair());
        Actor doctor = new Actor("SYNTH-DOCTOR-T1", AuthorRole.HEALTH_PROFESSIONAL, enc.generateKeyPair());
        Actor intruder = new Actor("SYNTH-INTRUDER-T1", AuthorRole.AUTHORIZED_THIRD_PARTY, enc.generateKeyPair());
        overlay.registerUser(patient, "DOB-SYNTH-T1", "DOC-SYNTH-T1", "r1");
        overlay.registerUser(doctor, "DOB-SYNTH-T2", "DOC-SYNTH-T2", "r2");
        overlay.registerUser(intruder, "DOB-SYNTH-T3", "DOC-SYNTH-T3", "r3");
        overlay.adminRegisterStandardArchetype("openEHR-EHR-EVALUATION.demographics.v1");
        overlay.adminRegisterStandardArchetype("openEHR-EHR-OBSERVATION.laboratory_test_result.v1");
        overlay.adminRegisterStandardArchetype("openEHR-EHR-EVALUATION.problem_diagnosis.v1");
        overlay.adminRegisterStandardArchetype("openEHR-EHR-OBSERVATION.blood_pressure.v1");

        String pid = patient.getOpenId();
        check("OpenID-style id is a deterministic single hash",
                pid.equals(br.unisinos.omniphr.middleware.security.Authenticator
                        .generateOpenId("SYNTH-PATIENT-T1", "DOB-SYNTH-T1", "DOC-SYNTH-T1")));

        Map<String, String> d = new LinkedHashMap<>();
        d.put("full_name", "SYNTH-PATIENT-T1");
        Datablock b0 = overlay.submitDatablock(patient, pid, new RawRecord(StandardFormat.OPENEHR,
                new Archetype("openEHR-EHR-EVALUATION.demographics.v1",
                        HealthCategory.DEMOGRAPHICS, d).toCanonicalString(),
                HealthCategory.DEMOGRAPHICS), device);
        String hl7 = "OBX|1|NM|718-7^Hemoglobin^LN|14.1|g/dL";
        Datablock b1 = overlay.submitDatablock(doctor, pid,
                new RawRecord(StandardFormat.HL7, hl7, HealthCategory.LAB_RESULT), provider);
        Datablock b2 = overlay.submitDatablock(doctor, pid,
                new RawRecord(StandardFormat.PROPRIETARY,
                        "Diagnosis: acute bronchitis\nBlood Pressure: 110/70",
                        HealthCategory.DIAGNOSIS), provider);

        check("Genesis block has sequence 0 and genesis pointer",
                b0.getSequence() == 0 && Datablock.GENESIS_PREVIOUS_HASH.equals(b0.getPreviousHash()));
        check("Chain pointers link block(n).previousHash to block(n-1).hash",
                b1.getPreviousHash().equals(b0.getHash()) && b2.getPreviousHash().equals(b1.getHash()));

        // Translator
        check("HL7 OBX translated to openEHR lab archetype via LOINC ontology",
                b1.getArchetypeId().equals("openEHR-EHR-OBSERVATION.laboratory_test_result.v1"));
        Archetype labContent = overlay.readContent(b1, patient);
        check("HL7 value mapped to archetype path 'hemoglobin'",
                "14.1 g/dL".equals(labContent.getItems().get("hemoglobin")));
        check("Proprietary free text resolved by NLP + synonyms to diagnosis archetype",
                b2.getArchetypeId().equals("openEHR-EHR-EVALUATION.problem_diagnosis.v1"));

        // Encryption
        Archetype demoContent = overlay.readContent(b0, patient);
        check("Patient decrypts own datablock content", "SYNTH-PATIENT-T1".equals(demoContent.getItems().get("full_name")));
        boolean intruderBlocked;
        try {
            overlay.readContent(b0, intruder);
            intruderBlocked = false;
        } catch (SecurityException e) {
            intruderBlocked = true;
        }
        check("Non-authorized reader cannot decrypt (no wrapped content key)", intruderBlocked);

        // Roles and privileges
        Page none = overlay.queryPhr(intruder.getOpenId(), pid, null, 1, 10);
        check("Access denied by default to third-party without grant", none.getItems().isEmpty());
        overlay.grantAccess(patient, doctor.getOpenId(), EnumSet.of(HealthCategory.LAB_RESULT));
        Page docView = overlay.queryPhr(doctor.getOpenId(), pid, null, 1, 10);
        check("Doctor sees exactly the granted category", docView.getItems().size() == 1
                && docView.getItems().get(0).getCategory() == HealthCategory.LAB_RESULT);
        check("Grant re-wraps content key for grantee",
                "14.1 g/dL".equals(overlay.readContent(b1, doctor).getItems().get("hemoglobin")));
        overlay.revokeAccess(patient, doctor.getOpenId());
        check("Revocation removes visibility at any time",
                overlay.queryPhr(doctor.getOpenId(), pid, null, 1, 10).getItems().isEmpty());

        // Pagination
        Page p1 = overlay.queryPhr(pid, pid, null, 1, 2);
        check("Pagination: page 1 holds the most recent blocks first",
                p1.getItems().size() == 2 && p1.getItems().get(0).getSequence() == 2 && p1.hasNext());
        Page p2 = overlay.queryPhr(pid, pid, null, 2, 2);
        check("Pagination: page 2 reaches the oldest block", p2.getItems().size() == 1
                && p2.getItems().get(0).getSequence() == 0 && !p2.hasNext());

        // Validator detects manipulation
        Datablock forged = new Datablock(b1.getBlockId(), b1.getPatientId(), b1.getSequence(),
                b1.getCreatedAt(), b1.getPreviousHash(), b1.getArchetypeId(), b1.getCategory(),
                b1.getSourceStandard(), b1.getAuthorId(), b1.getAuthorRole(),
                b1.getEncryptedContent(), b1.getEncryptedContentKeys(),
                "beef" + b1.getContentHash().substring(4), intruder.sign("x"));
        for (ChordNode n : net2.allNodes()) {
            if (n.getBlock(b1.getBlockId()) != null) {
                n.storeBlock(forged);
            }
        }
        overlay.getMiddleware().getMessageRouter().clearCache();
        boolean tamperDetected;
        try {
            overlay.queryPhr(pid, pid, null, 1, 10);
            tamperDetected = false;
        } catch (SecurityException e) {
            tamperDetected = true;
        }
        check("Validator detects manipulated replica on chain validation", tamperDetected);
        for (ChordNode n : net2.allNodes()) {
            if (n.getBlock(b1.getBlockId()) != null) {
                n.storeBlock(b1);
            }
        }
        overlay.getMiddleware().getMessageRouter().clearCache();

        // Replication resilience
        ChordId key1 = ChordId.of(b1.getBlockId());
        ChordNode owner = overlay.findSuccessor(key1, new Hops());
        owner.fail();
        net2.unregister(owner);
        nm.stabilizationRound();
        overlay.getMiddleware().getMessageRouter().clearCache();
        Datablock fetched = overlay.getMiddleware().getDistributor().fetch(b1.getBlockId(), new Hops());
        check("Datablock survives owner failure through successor replicas", fetched != null);

        // Authenticator blocking/recovery
        Authenticator auth = overlay.getMiddleware().getAuthenticator();
        for (int i = 0; i < 3; i++) {
            try {
                String c = auth.requestChallenge(pid);
                auth.authenticate(pid, intruder.sign(c));
            } catch (SecurityException ignored) {
            }
        }
        boolean blocked;
        try {
            auth.requestChallenge(pid);
            blocked = false;
        } catch (SecurityException e) {
            blocked = true;
        }
        check("Account blocked after " + br.unisinos.omniphr.middleware.security.Authenticator.MAX_FAILED_ATTEMPTS
                + " failed attempts", blocked);
        check("Lost access recovered with recovery code", auth.recoverAccess(pid, "r1"));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        return failed == 0 ? 0 : 1;
    }

    private ChordNode bruteForceSuccessor(List<ChordNode> sortedRing, ChordId key) {
        for (ChordNode n : sortedRing) {
            if (n.getId().value().compareTo(key.value()) >= 0) {
                return n;
            }
        }
        return sortedRing.get(0);
    }
}
