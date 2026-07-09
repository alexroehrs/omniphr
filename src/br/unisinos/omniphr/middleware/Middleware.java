package br.unisinos.omniphr.middleware;

import br.unisinos.omniphr.middleware.datablock.Distributor;
import br.unisinos.omniphr.middleware.datablock.MessageRouter;
import br.unisinos.omniphr.middleware.datablock.NodesManager;
import br.unisinos.omniphr.middleware.datablock.Translator;
import br.unisinos.omniphr.middleware.datablock.Validator;
import br.unisinos.omniphr.middleware.security.Authenticator;
import br.unisinos.omniphr.middleware.security.DigitalSigner;
import br.unisinos.omniphr.middleware.security.Encryptor;
import br.unisinos.omniphr.middleware.security.RolesAndPrivileges;
import br.unisinos.omniphr.net.NetworkEnvironment;
import br.unisinos.omniphr.p2p.chord.ChordNode;
import br.unisinos.omniphr.p2p.pubsub.PubSubService;
import br.unisinos.omniphr.repository.RelationalRepository;
import br.unisinos.omniphr.repository.SemanticRepository;

/**
 * The middleware present in each routing overlay, a logical abstraction of
 * all modules and business components of the architecture.
 *
 * It aggregates the two modules:
 * - Datablock and Service Module: Translator, Distributor, Validator,
 *   Nodes Manager and Message Router;
 * - Security and Privacy Module: Encryptor, Digital Signer, Authenticator
 *   and Roles and Privileges;
 * and the repositories (relational and semantic).
 */
public class Middleware {

    // Repositories
    private final RelationalRepository relationalRepository = new RelationalRepository();
    private final SemanticRepository semanticRepository = new SemanticRepository();

    // Datablock and Service Module
    private final Translator translator;
    private final Distributor distributor;
    private final Validator validator;
    private final NodesManager nodesManager;
    private final MessageRouter messageRouter;

    // Security and Privacy Module
    private final Encryptor encryptor = new Encryptor();
    private final DigitalSigner digitalSigner = new DigitalSigner();
    private final Authenticator authenticator;
    private final RolesAndPrivileges rolesAndPrivileges;

    public Middleware(NetworkEnvironment network, ChordNode overlayNode,
                      String overlayName, PubSubService pubSub) {
        this.messageRouter = new MessageRouter();
        this.translator = new Translator(semanticRepository);
        this.distributor = new Distributor(network, overlayNode, messageRouter);
        this.validator = new Validator(digitalSigner);
        this.nodesManager = new NodesManager(network, pubSub, overlayName);
        this.authenticator = new Authenticator(relationalRepository, digitalSigner);
        this.rolesAndPrivileges = new RolesAndPrivileges(relationalRepository);
    }

    public RelationalRepository getRelationalRepository() {
        return relationalRepository;
    }

    public SemanticRepository getSemanticRepository() {
        return semanticRepository;
    }

    public Translator getTranslator() {
        return translator;
    }

    public Distributor getDistributor() {
        return distributor;
    }

    public Validator getValidator() {
        return validator;
    }

    public NodesManager getNodesManager() {
        return nodesManager;
    }

    public MessageRouter getMessageRouter() {
        return messageRouter;
    }

    public Encryptor getEncryptor() {
        return encryptor;
    }

    public DigitalSigner getDigitalSigner() {
        return digitalSigner;
    }

    public Authenticator getAuthenticator() {
        return authenticator;
    }

    public RolesAndPrivileges getRolesAndPrivileges() {
        return rolesAndPrivileges;
    }
}
