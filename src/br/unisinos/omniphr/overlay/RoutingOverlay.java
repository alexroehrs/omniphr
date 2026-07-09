package br.unisinos.omniphr.overlay;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.Datablock;
import br.unisinos.omniphr.core.HashUtil;
import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.core.Page;
import br.unisinos.omniphr.core.StandardFormat;
import br.unisinos.omniphr.core.openehr.Archetype;
import br.unisinos.omniphr.middleware.Middleware;
import br.unisinos.omniphr.middleware.datablock.MessageRouter;
import br.unisinos.omniphr.middleware.datablock.RawRecord;
import br.unisinos.omniphr.middleware.datablock.Validator;
import br.unisinos.omniphr.middleware.security.Encryptor;
import br.unisinos.omniphr.net.NetworkEnvironment;
import br.unisinos.omniphr.node.Actor;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.chord.Hops;
import br.unisinos.omniphr.p2p.pubsub.PubSubMessage;
import br.unisinos.omniphr.p2p.pubsub.PubSubService;
import br.unisinos.omniphr.repository.BlockRef;
import br.unisinos.omniphr.repository.UserAccount;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing overlay node (superpeer) of the OmniPHR model: an application
 * server with defined responsibilities, whose main goal is to maintain and
 * locate PHR datablocks when required and to validate whether the chaining
 * is intact or suffered some manipulation.
 *
 * Responsibilities: (a) maintain system user registers; (b) keep PHR data,
 * including new and updated datablocks; (c) query datablocks to assemble
 * the PHR when required; (d) maintain access permissions to health records;
 * and (e) maintain access profiles to health records. In addition, it
 * offers functions granted to system administrators: maintain the types of
 * profiles, the health datablocks inherent in the standard, and the other
 * interconnected standards of health records.
 *
 * Every public operation enters through the Message Router, which forwards
 * the request to the responsible components.
 */
public class RoutingOverlay extends ChordNode {

    private final Middleware middleware;
    private final PubSubService pubSub;

    /** Chain head (last datablock) of each patient chain kept by this overlay. */
    private final Map<String, Datablock> chainHeads = new ConcurrentHashMap<>();

    public RoutingOverlay(String name, NetworkEnvironment network, int subnetId) {
        super(name, network, subnetId);
        this.pubSub = new PubSubService(name);
        this.middleware = new Middleware(network, this, name, pubSub);
        wireMessageRouter();
    }

    public static String patientTopic(String patientId) {
        return "omniphr/phr/" + HashUtil.sha256Hex(patientId).substring(0, 16);
    }

    /** All requests are packaged and forwarded by the Message Router. */
    private void wireMessageRouter() {
        MessageRouter router = middleware.getMessageRouter();
        router.registerHandler(MessageRouter.RequestType.REGISTER_USER,
                payload -> doRegisterUser((RegisterRequest) payload));
        router.registerHandler(MessageRouter.RequestType.SUBMIT_DATABLOCK,
                payload -> doSubmitDatablock((SubmitRequest) payload));
        router.registerHandler(MessageRouter.RequestType.FETCH_DATABLOCK,
                payload -> middleware.getDistributor().fetch((String) payload, new Hops()));
        router.registerHandler(MessageRouter.RequestType.QUERY_PHR,
                payload -> doQueryPhr((QueryRequest) payload));
        router.registerHandler(MessageRouter.RequestType.GRANT_ACCESS,
                payload -> doGrant((GrantRequest) payload));
        router.registerHandler(MessageRouter.RequestType.REVOKE_ACCESS,
                payload -> doRevoke((GrantRequest) payload));
    }

    // ==================================================================
    // (a) maintain system user registers
    // ==================================================================

    public UserAccount registerUser(Actor actor, String birthDate, String documentNumber,
                                    String recoveryCode) {
        RegisterRequest req = new RegisterRequest(actor, birthDate, documentNumber, recoveryCode);
        return (UserAccount) middleware.getMessageRouter()
                .route(new MessageRouter.Request(MessageRouter.RequestType.REGISTER_USER, req));
    }

    private UserAccount doRegisterUser(RegisterRequest req) {
        UserAccount account = middleware.getAuthenticator().register(
                req.actor.getFullName(), req.birthDate, req.documentNumber,
                req.actor.getRole(), req.actor.getPublicKey(), req.recoveryCode);
        req.actor.setOpenId(account.getOpenId());
        return account;
    }

    // ==================================================================
    // (b) keep PHR data, including new and update datablocks
    // ==================================================================

    /**
     * Entry of a new health datablock, following the model pipeline:
     * Translator (only if the source standard differs from the adopted one)
     * -> Encryptor -> Digital Signer at the source -> Validator
     * authenticates the block before chaining -> Distributor stores the
     * original and the replicas -> publish-subscribe notification.
     */
    public Datablock submitDatablock(Actor author, String patientId, RawRecord raw,
                                     ChordNode originNode) {
        SubmitRequest req = new SubmitRequest(author, patientId, raw, originNode);
        return (Datablock) middleware.getMessageRouter()
                .route(new MessageRouter.Request(MessageRouter.RequestType.SUBMIT_DATABLOCK, req));
    }

    private Datablock doSubmitDatablock(SubmitRequest req) {
        UserAccount authorAccount = middleware.getRelationalRepository().findUser(req.author.getOpenId());
        if (authorAccount == null) {
            throw new SecurityException("author is not a registered user");
        }
        if (authorAccount.isBlocked()) {
            throw new SecurityException("author account is blocked");
        }

        // --- Translator: input gateway ---
        Archetype archetype;
        if (req.raw.getStandard() == StandardFormat.OPENEHR) {
            // provider uses the OmniPHR standard: component not triggered
            archetype = middleware.getTranslator().toOmniPhrStandard(req.raw);
        } else {
            archetype = middleware.getTranslator().toOmniPhrStandard(req.raw);
        }

        // administrator registry of datablock types of the standard
        if (!middleware.getRelationalRepository().archetypeIsRegistered(archetype.getArchetypeId())) {
            throw new IllegalArgumentException("archetype not registered in the standard: "
                    + archetype.getArchetypeId());
        }

        String canonical = archetype.toCanonicalString();
        String contentHash = HashUtil.sha256Hex(canonical);

        // --- Encryptor: readers = patient + currently granted users ---
        Map<String, PublicKey> readers = new HashMap<>();
        UserAccount patient = middleware.getRelationalRepository().findUser(req.patientId);
        if (patient == null) {
            throw new SecurityException("unknown patient " + req.patientId);
        }
        readers.put(req.patientId, patient.getPublicKey());
        if (!req.author.getOpenId().equals(req.patientId)) {
            readers.put(req.author.getOpenId(), req.author.getPublicKey());
        }
        for (Map.Entry<String, Set<HealthCategory>> g :
                grantedReaders(req.patientId, archetype.getCategory()).entrySet()) {
            UserAccount grantee = middleware.getRelationalRepository().findUser(g.getKey());
            if (grantee != null) {
                readers.put(g.getKey(), grantee.getPublicKey());
            }
        }
        Encryptor.EncryptedPayload payload = middleware.getEncryptor().encrypt(canonical, readers);

        // --- chain position ---
        Datablock head = chainHeads.get(req.patientId);
        long sequence = head == null ? 0 : head.getSequence() + 1;
        String previousHash = head == null ? Datablock.GENESIS_PREVIOUS_HASH : head.getHash();
        String blockId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();

        // --- Digital Signer: the author signs at the source ---
        String header = Datablock.buildHeaderString(blockId, req.patientId, sequence, createdAt,
                previousHash, archetype.getArchetypeId(), archetype.getCategory(),
                req.author.getOpenId(), req.author.getRole(), contentHash);
        byte[] signature = req.author.sign(header);

        Datablock block = new Datablock(blockId, req.patientId, sequence, createdAt, previousHash,
                archetype.getArchetypeId(), archetype.getCategory(), req.raw.getStandard(),
                req.author.getOpenId(), req.author.getRole(), payload.ciphertext,
                payload.wrappedKeys, contentHash, signature);

        // --- Validator: authenticate before chaining ---
        Validator.ValidationResult validation = middleware.getValidator()
                .authenticateNewBlock(block, head, authorAccount.getPublicKey());
        if (!validation.isValid()) {
            throw new SecurityException("datablock rejected: " + validation.getIssues());
        }

        // --- Distributor: originals + DHT replicas ---
        middleware.getDistributor().store(block, req.originNode);
        if (req.originNode instanceof br.unisinos.omniphr.node.RegularNode
                && block.getAuthorRole() != AuthorRole.PATIENT) {
            ((br.unisinos.omniphr.node.RegularNode) req.originNode).storeOriginal(block);
        }

        chainHeads.put(req.patientId, block);
        middleware.getRelationalRepository().appendBlockRef(req.patientId,
                new BlockRef(blockId, block.getHash(), sequence, archetype.getCategory(),
                        archetype.getArchetypeId(), createdAt,
                        req.originNode == null ? getName() : req.originNode.getName()));

        // --- publish-subscribe notification ---
        pubSub.publish(patientTopic(req.patientId), new PubSubMessage(
                sequence == 0 ? PubSubMessage.Type.DATABLOCK_PUBLISHED : PubSubMessage.Type.DATABLOCK_UPDATED,
                req.author.getOpenId(), blockId,
                archetype.getCategory() + " seq=" + sequence));
        return block;
    }

    private Map<String, Set<HealthCategory>> grantedReaders(String patientId, HealthCategory category) {
        Map<String, Set<HealthCategory>> out = new HashMap<>();
        for (String grantee : knownGrantees(patientId)) {
            Set<HealthCategory> cats = middleware.getRolesAndPrivileges().grantedCategories(patientId, grantee);
            if (cats.contains(category)) {
                out.put(grantee, cats);
            }
        }
        return out;
    }

    private final Map<String, Set<String>> granteesByPatient = new ConcurrentHashMap<>();

    private Set<String> knownGrantees(String patientId) {
        return granteesByPatient.getOrDefault(patientId, Collections.<String>emptySet());
    }

    // ==================================================================
    // (c) querying datablocks to assembly PHR when required
    // ==================================================================

    /**
     * Assembles the unified viewpoint of the PHR: fetches the distributed
     * datablocks (through cache/ring), validates that the chaining is
     * intact, filters by the requester's privileges and returns the
     * requested page, most recent first.
     */
    public Page queryPhr(String requesterId, String patientId, HealthCategory categoryFilter,
                         int pageNumber, int pageSize) {
        QueryRequest req = new QueryRequest(requesterId, patientId, categoryFilter, pageNumber, pageSize);
        return (Page) middleware.getMessageRouter()
                .route(new MessageRouter.Request(MessageRouter.RequestType.QUERY_PHR, req));
    }

    private Page doQueryPhr(QueryRequest req) {
        List<BlockRef> refs = middleware.getRelationalRepository().chainOf(req.patientId);
        List<Datablock> fetched = new ArrayList<>();
        Hops totalHops = new Hops();
        for (BlockRef ref : refs) {
            Datablock b = middleware.getDistributor().fetch(ref.getBlockId(), totalHops);
            if (b != null) {
                fetched.add(b);
            }
        }
        // validate whether the chaining is intact or had some manipulation
        Validator.ValidationResult result = middleware.getValidator().validateChain(fetched, authorId -> {
            UserAccount u = middleware.getRelationalRepository().findUser(authorId);
            return u == null ? null : u.getPublicKey();
        });
        if (!result.isValid()) {
            throw new SecurityException("PHR chain validation failed: " + result.getIssues());
        }
        // privilege filtering: the requester only receives allowed categories
        List<Datablock> visible = new ArrayList<>();
        for (Datablock b : fetched) {
            if (req.categoryFilter != null && b.getCategory() != req.categoryFilter) {
                continue;
            }
            if (middleware.getRolesAndPrivileges().canAccess(req.requesterId, req.patientId, b.getCategory())) {
                visible.add(b);
            }
        }
        return Page.of(visible, req.pageNumber, req.pageSize);
    }

    // ==================================================================
    // (d) maintain access permissions / (e) access profiles
    // ==================================================================

    /**
     * The patient (master controller of the own PHR) grants privileges and
     * re-wraps the content keys of the existing blocks for the grantee.
     */
    public void grantAccess(Actor patient, String granteeId, Set<HealthCategory> categories) {
        middleware.getMessageRouter().route(new MessageRouter.Request(
                MessageRouter.RequestType.GRANT_ACCESS,
                new GrantRequest(patient, granteeId, categories)));
    }

    private Object doGrant(GrantRequest req) {
        String patientId = req.patient.getOpenId();
        middleware.getRolesAndPrivileges().grant(patientId, req.granteeId, req.categories);
        granteesByPatient.computeIfAbsent(patientId, p -> ConcurrentHashMap.newKeySet()).add(req.granteeId);
        UserAccount grantee = middleware.getRelationalRepository().findUser(req.granteeId);
        if (grantee != null) {
            for (BlockRef ref : middleware.getRelationalRepository().chainOf(patientId)) {
                if (!req.categories.contains(ref.getCategory())) {
                    continue;
                }
                Datablock block = middleware.getDistributor().fetch(ref.getBlockId(), new Hops());
                if (block != null) {
                    byte[] patientWrapped = block.getEncryptedContentKeys().get(patientId);
                    if (patientWrapped != null) {
                        byte[] rewrapped = middleware.getEncryptor().rewrapForGrantee(
                                patientWrapped, req.patient.getPrivateKey(), grantee.getPublicKey());
                        block.addEncryptedContentKey(req.granteeId, rewrapped);
                    }
                }
            }
        }
        pubSub.publish(patientTopic(patientId), new PubSubMessage(PubSubMessage.Type.ACCESS_GRANTED,
                patientId, req.granteeId, "categories=" + req.categories));
        return null;
    }

    /** The patient revokes at any time. */
    public void revokeAccess(Actor patient, String granteeId) {
        middleware.getMessageRouter().route(new MessageRouter.Request(
                MessageRouter.RequestType.REVOKE_ACCESS,
                new GrantRequest(patient, granteeId, Collections.<HealthCategory>emptySet())));
    }

    private Object doRevoke(GrantRequest req) {
        String patientId = req.patient.getOpenId();
        middleware.getRolesAndPrivileges().revoke(patientId, req.granteeId);
        Set<String> set = granteesByPatient.get(patientId);
        if (set != null) {
            set.remove(req.granteeId);
        }
        for (BlockRef ref : middleware.getRelationalRepository().chainOf(patientId)) {
            Datablock block = middleware.getDistributor().fetch(ref.getBlockId(), new Hops());
            if (block != null) {
                block.removeEncryptedContentKey(req.granteeId);
            }
        }
        pubSub.publish(patientTopic(patientId), new PubSubMessage(PubSubMessage.Type.ACCESS_REVOKED,
                patientId, req.granteeId, "all categories"));
        return null;
    }

    // ==================================================================
    // Administrator functions
    // ==================================================================

    /** Maintain the types of profiles. */
    public void adminDefineProfileType(String organizationId, String profileName,
                                       Set<HealthCategory> scope) {
        middleware.getRolesAndPrivileges().defineProfile(organizationId, profileName, scope);
    }

    /** Maintain the health datablock types inherent in the standard. */
    public void adminRegisterStandardArchetype(String archetypeId) {
        middleware.getRelationalRepository().registerArchetype(archetypeId);
    }

    /** Maintain the other interconnected standards of health records. */
    public void adminAddStandardMapping(String subject, String predicate, String object) {
        middleware.getSemanticRepository().add(subject, predicate, object);
    }

    // ==================================================================

    /** Decrypts the content of a block for an authorized reader. */
    public Archetype readContent(Datablock block, Actor reader) {
        byte[] wrapped = block.getEncryptedContentKeys().get(reader.getOpenId());
        if (wrapped == null) {
            throw new SecurityException(reader.getFullName() + " has no content key for this datablock");
        }
        String canonical = middleware.getEncryptor().decrypt(block.getEncryptedContent(),
                wrapped, reader.getPrivateKey());
        return Archetype.fromCanonicalString(canonical);
    }

    public Middleware getMiddleware() {
        return middleware;
    }

    public PubSubService getPubSub() {
        return pubSub;
    }

    // -------------------- request payloads --------------------

    private static final class RegisterRequest {
        final Actor actor;
        final String birthDate;
        final String documentNumber;
        final String recoveryCode;

        RegisterRequest(Actor actor, String birthDate, String documentNumber, String recoveryCode) {
            this.actor = actor;
            this.birthDate = birthDate;
            this.documentNumber = documentNumber;
            this.recoveryCode = recoveryCode;
        }
    }

    private static final class SubmitRequest {
        final Actor author;
        final String patientId;
        final RawRecord raw;
        final ChordNode originNode;

        SubmitRequest(Actor author, String patientId, RawRecord raw, ChordNode originNode) {
            this.author = author;
            this.patientId = patientId;
            this.raw = raw;
            this.originNode = originNode;
        }
    }

    private static final class QueryRequest {
        final String requesterId;
        final String patientId;
        final HealthCategory categoryFilter;
        final int pageNumber;
        final int pageSize;

        QueryRequest(String requesterId, String patientId, HealthCategory categoryFilter,
                     int pageNumber, int pageSize) {
            this.requesterId = requesterId;
            this.patientId = patientId;
            this.categoryFilter = categoryFilter;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
        }
    }

    private static final class GrantRequest {
        final Actor patient;
        final String granteeId;
        final Set<HealthCategory> categories;

        GrantRequest(Actor patient, String granteeId, Set<HealthCategory> categories) {
            this.patient = patient;
            this.granteeId = granteeId;
            this.categories = categories;
        }
    }
}
