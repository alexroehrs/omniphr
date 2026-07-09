package br.unisinos.omniphr.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Relational repository of the routing overlay. Kept in memory in this
 * reference implementation, with table-like structures for: user registers,
 * the index of each patient's datablock chain, and the registry of datablock
 * types inherent in the adopted standard.
 */
public class RelationalRepository {

    /** Table of system users, keyed by OpenID-style identifier. */
    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();

    /** Index of each patient's chain: patientId -> ordered block references. */
    private final Map<String, List<BlockRef>> patientChains = new ConcurrentHashMap<>();

    /** Datablock types (archetypes) inherent in the adopted standard. */
    private final Set<String> registeredArchetypes = new CopyOnWriteArraySet<>();

    // -------------------- users --------------------

    public void saveUser(UserAccount account) {
        users.put(account.getOpenId(), account);
    }

    public UserAccount findUser(String openId) {
        return users.get(openId);
    }

    public boolean userExists(String openId) {
        return users.containsKey(openId);
    }

    // -------------------- patient chain index --------------------

    public synchronized void appendBlockRef(String patientId, BlockRef ref) {
        patientChains.computeIfAbsent(patientId, p -> Collections.synchronizedList(new ArrayList<>())).add(ref);
    }

    public List<BlockRef> chainOf(String patientId) {
        List<BlockRef> refs = patientChains.get(patientId);
        return refs == null ? new ArrayList<>() : new ArrayList<>(refs);
    }

    public BlockRef chainHead(String patientId) {
        List<BlockRef> refs = patientChains.get(patientId);
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        return refs.get(refs.size() - 1);
    }

    // -------------------- standard archetype registry --------------------

    public void registerArchetype(String archetypeId) {
        registeredArchetypes.add(archetypeId);
    }

    public boolean archetypeIsRegistered(String archetypeId) {
        return registeredArchetypes.contains(archetypeId);
    }

    public Set<String> getRegisteredArchetypes() {
        return registeredArchetypes;
    }
}
