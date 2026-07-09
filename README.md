# OmniPHR — Java reference implementation

## License and citation

This code is **open source**, released under the [MIT License](LICENSE).
If you use this code or the OmniPHR model, in whole or in part, in academic or
commercial work, **please cite the paper that proposed the model**:

> Roehrs, A., da Costa, C. A., & da Rosa Righi, R. (2017). **OmniPHR: A distributed
> architecture model to integrate personal health records.** *Journal of Biomedical
> Informatics*, 71, 70–81. https://doi.org/10.1016/j.jbi.2017.05.012

BibTeX:

```bibtex
@article{Roehrs2017OmniPHR,
  title   = {OmniPHR: A distributed architecture model to integrate personal health records},
  author  = {Roehrs, Alex and da Costa, Cristiano Andr{\'e} and da Rosa Righi, Rodrigo},
  journal = {Journal of Biomedical Informatics},
  volume  = {71},
  pages   = {70--81},
  year    = {2017},
  doi     = {10.1016/j.jbi.2017.05.012}
}
```

A machine-readable [`CITATION.cff`](CITATION.cff) is included, so GitHub displays a
"Cite this repository" button.

## Overview

OmniPHR is a distributed architecture model to integrate personal health records
(PHR). The patient's health history is divided into datablocks chained as in
blockchain technology, encrypted, digitally signed by the responsible informant and
distributed over a P2P network, giving patients and healthcare providers a unified,
up-to-date and interoperable view of the PHR.

**Java 8** (source and bytecode compatible with the Java version current at the time
the model was designed), **no external dependencies** (cryptography via the JDK's
JCA). Runs unchanged on any later JDK. Simulated single-process P2P network with N
nodes.

Note: AES-256 requires the JCE Unlimited Strength policy on Java 8 builds older than
8u161; from 8u161 onward it is enabled by default.

## How to run

```bash
./run.sh demo                       # full end-to-end scenario
./run.sh selftest                   # 20 verification checks
./run.sh eval                       # evaluation setups 1-4
./run.sh eval --full --duration 60  # all 10 setups (100 to 3200 nodes)
```

Or manually: `javac -d bin $(find src -name "*.java")` and
`java -cp bin br.unisinos.omniphr.Main demo`.
(Without `javac` on the PATH, use `java -m jdk.compiler/com.sun.tools.javac.Main` instead.)

## Architecture overview

| Concept | Description | Code (`src/br/unisinos/omniphr/`) |
|---|---|---|
| Chained datablocks | Logical division of the PHR; each block carries creation time, hash pointer to the previous block, author id/role and digital signature | `core/Datablock.java`, `core/HealthCategory.java` |
| Pagination | Unified view returned page by page, most recent data first | `core/Page.java` |
| openEHR content | Clinical content as simplified openEHR archetype instances | `core/openehr/Archetype.java` |
| Chord DHT | Finger table (1st entry = successor), successor list as replication engine, O(log N) lookup, consistent hashing, node join/leave | `p2p/chord/ChordNode.java`, `ChordId.java` |
| Publish-subscribe | Indirect communication among nodes, hosted on the routing overlay | `p2p/pubsub/PubSubService.java`, `PubSubMessage.java` |
| Routing overlay (superpeer) | Application server that maintains user registers, keeps and locates PHR datablocks, assembles the unified view, and manages permissions and profiles; also exposes administrator functions (profile types, archetype registry, standard mappings) | `overlay/RoutingOverlay.java` |
| Middleware | Logical abstraction of all modules, present in each routing overlay | `middleware/Middleware.java` |
| **Translator** | Input/output gateway; triggered only when the source standard differs from openEHR; handles other open standards (HL7/LOINC via ontology) and proprietary formats (NLP + semantic database) | `middleware/datablock/Translator.java`, `repository/SemanticRepository.java` |
| **Distributor** | Originals stay at the source organization (patient data stays on the overlay); copies replicated through the DHT | `middleware/datablock/Distributor.java` |
| **Validator** | Chain integrity, consistency and sequencing; authenticates each new block before chaining | `middleware/datablock/Validator.java` |
| **Nodes Manager** | Node input/output per DHT rules, identifier generation, network notification, replica re-dissemination | `middleware/datablock/NodesManager.java` |
| **Message Router** | Packages and routes requests to the components; time-limited in-memory cache of recently requested datablocks | `middleware/datablock/MessageRouter.java` |
| **Encryptor** | Public-key base; private key secret, public key distributed with the user identifier | `middleware/security/Encryptor.java` |
| **Digital Signer** | Per-user signature over each informed datablock; verifies unchanged copies | `middleware/security/DigitalSigner.java` |
| **Authenticator** | OpenID-style identifier (single hash of natural keys, avoids duplication), challenge-response login, blocking and access recovery | `middleware/security/Authenticator.java` |
| **Roles and Privileges** | Personal approach (patient grants/revokes at any time; master controller) and organizational approach (profiles per organization); effective privilege = intersection | `middleware/security/RolesAndPrivileges.java` |
| Repositories | Relational (users, chain index, archetype registry) and semantic (ontology triples) | `repository/RelationalRepository.java`, `SemanticRepository.java` |
| Sources / leaf nodes | EMR/EHR systems, patient devices, sensors/IoT, acting as providers or consumers | `node/RegularNode.java`, `node/Actor.java` |
| Network environment | Subnetworks served by routing overlays and interconnected by backbone routers, with simulated link latencies | `net/SimulatedNetwork.java` |
| Evaluation harness | 10 setups (100–3200 nodes), tests A/B, churn up to 5%, random messages at up to 1 s intervals; collects MP, OHC and OL | `eval/Evaluation.java`, `net/Metrics.java` |

The demo (`demo/DemoScenario.java`) runs a typical scenario on a 12-node network with
4 subnetworks: registration with a unique id, demographic genesis block under the
patient's responsibility, native openEHR blocks from a hospital, translated HL7 from a
laboratory, legacy free text from a clinic via NLP, sensor stream, unified paginated
view, privilege filtering, revocation, tamper detection, node failure with replica
recovery, and account blocking/recovery.

All demo and test data is synthetic and fully non-identifiable (placeholder tokens
such as `SYNTH-NAME-001`, `DOB-SYNTH-001`, `DOC-SYNTH-001`; no real names, birth
dates, documents, addresses or organization names), in line with data protection
regulations such as LGPD and GDPR. The remaining clinical values (e.g. blood
pressure, hemoglobin) are generic physiological examples not linked to any
identifiable person.

## Evaluation results

Metrics collected per test: Messages Present (MP, average number of messages in
transmission at every instant), One-way Hop Count (OHC) and One-way Latency (OL).
With `eval --full --duration 60` (absolute values depend on the simulated latency
parameters; the scalability pattern is what matters):

- **OHC** grows logarithmically with the number of nodes: 4.28 → 6.77 hops from 100
  to 3200 nodes — O(log N) lookup confirmed;
- **MP** grows proportionally to the number of nodes;
- **OL** remains stable even with 32x more nodes (0.22 s → 0.52 s): the topology,
  employing the Chord algorithm, answers an increasing number of users and requests
  without increasing the delivery time significantly.

## Implementation decisions

- **Hybrid encryption**: content encrypted with AES-256-GCM and the content key
  wrapped with RSA-OAEP per authorized reader — keeps the public-key base of the
  model and enables multiple readers; on access grants, the patient re-wraps the
  keys, consistent with the patient as master controller.
- **Chaining pointers in the clear**: the pointers are hashes (they do not expose
  clinical content) and remain readable so the routing overlay can validate that the
  chaining is intact.
- **Per-overlay user registry**: state synchronization between overlays is left open;
  the demo uses one overlay as the service point, with storage and replication
  distributed across the whole ring.
- **NLP and ontology** in the Translator are minimal implementations with clear
  extension points.
- **In-process network simulator** with per-hop latencies (intra-subnetwork,
  inter-subnetwork and backbone), instead of an external network simulation
  framework.
