package br.unisinos.omniphr.node;

import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.net.SimulatedNetwork;
import br.unisinos.omniphr.p2p.chord.ChordNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regular (leaf) node of the network: a healthcare provider system (EMR/EHR
 * database of a hospital, laboratory or clinic), a patient device or a
 * sensor.
 *
 * Devices can join the system as providers or consumers of PHR data. Each
 * regular node participates in the Chord ring and can hold replicated
 * datablocks; additionally, it keeps the originals it created, since the
 * original data reported by a healthcare provider remains stored in the
 * health organization.
 */
public class RegularNode extends ChordNode {

    /** Kind of data source. */
    public enum Kind {
        HOSPITAL_EHR, LABORATORY, CLINIC_EMR, FIRST_AID_STATION,
        PATIENT_DEVICE, WEARABLE_SENSOR
    }

    private final Kind kind;
    private final StandardFormat sourceStandard;   // standard used at this source

    /** Originals created at this organization/device. */
    private final Map<String, Datablock> originStore = new ConcurrentHashMap<>();

    public RegularNode(String name, SimulatedNetwork network, int subnetId,
                       Kind kind, StandardFormat sourceStandard) {
        super(name, network, subnetId);
        this.kind = kind;
        this.sourceStandard = sourceStandard;
    }

    public void storeOriginal(Datablock block) {
        originStore.put(block.getBlockId(), block);
    }

    public Datablock getOriginal(String blockId) {
        return originStore.get(blockId);
    }

    public int originalsCount() {
        return originStore.size();
    }

    public Kind getKind() {
        return kind;
    }

    public StandardFormat getSourceStandard() {
        return sourceStandard;
    }

    @Override
    public String toString() {
        return getName() + " [" + kind + ", " + sourceStandard + "]";
    }
}
