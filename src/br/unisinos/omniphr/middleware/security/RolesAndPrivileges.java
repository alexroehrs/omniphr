package br.unisinos.omniphr.middleware.security;

import br.unisinos.omniphr.core.AuthorRole;
import br.unisinos.omniphr.core.HealthCategory;
import br.unisinos.omniphr.repository.RelationalRepository;
import br.unisinos.omniphr.repository.UserAccount;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Roles and Privileges component of the Security and Privacy Module.
 *
 * The component has two approaches. The first is personal: individual
 * control of the permissions granted to other users over the own PHR — the
 * patient may grant access privileges to health professionals or
 * third-parties, and revoke them at any time. The second is organizational:
 * each health organization creates and maintains the profiles of its health
 * professionals. The master controller of the PHR remains with the patient,
 * following the first approach.
 *
 * Consequently, for a health professional the effective privilege is the
 * intersection between what the patient granted personally and the scope of
 * the professional's organizational profile.
 */
public class RolesAndPrivileges {

    private final RelationalRepository repository;

    /** Personal approach: patientId -> (granteeId -> granted categories). */
    private final Map<String, Map<String, Set<HealthCategory>>> personalGrants = new ConcurrentHashMap<>();

    /** Organizational approach: orgId -> (profileName -> profile scope). */
    private final Map<String, Map<String, Set<HealthCategory>>> organizationalProfiles = new ConcurrentHashMap<>();

    private final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());

    public RolesAndPrivileges(RelationalRepository repository) {
        this.repository = repository;
    }

    // ------------------- organizational approach -------------------

    /** Administrator function: maintain the types of profiles. */
    public void defineProfile(String organizationId, String profileName, Set<HealthCategory> scope) {
        organizationalProfiles.computeIfAbsent(organizationId, o -> new ConcurrentHashMap<>())
                .put(profileName, EnumSet.copyOf(scope));
        audit("PROFILE_DEFINED org=" + organizationId + " profile=" + profileName + " scope=" + scope);
    }

    /** Each health organization defines the profiles of its professionals. */
    public void assignProfile(String professionalId, String organizationId, String profileName) {
        UserAccount account = repository.findUser(professionalId);
        if (account == null) {
            throw new IllegalArgumentException("unknown professional " + professionalId);
        }
        account.setOrganizationId(organizationId);
        account.setProfileName(profileName);
        audit("PROFILE_ASSIGNED professional=" + professionalId + " org=" + organizationId
                + " profile=" + profileName);
    }

    // ------------------- personal approach -------------------

    /** The patient grants access privileges over given categories. */
    public void grant(String patientId, String granteeId, Set<HealthCategory> categories) {
        personalGrants.computeIfAbsent(patientId, p -> new ConcurrentHashMap<>())
                .merge(granteeId, EnumSet.copyOf(categories), (a, b) -> {
                    a.addAll(b);
                    return a;
                });
        audit("ACCESS_GRANTED patient=" + patientId + " grantee=" + granteeId + " categories=" + categories);
    }

    /** The patient revokes at any time. */
    public void revoke(String patientId, String granteeId) {
        Map<String, Set<HealthCategory>> grants = personalGrants.get(patientId);
        if (grants != null) {
            grants.remove(granteeId);
        }
        audit("ACCESS_REVOKED patient=" + patientId + " grantee=" + granteeId);
    }

    // ------------------- decision -------------------

    /**
     * Access decision for a requester over one category of a patient's PHR.
     * The patient is always the master controller of the own record.
     */
    public boolean canAccess(String requesterId, String patientId, HealthCategory category) {
        boolean decision = decide(requesterId, patientId, category);
        audit("ACCESS_" + (decision ? "ALLOWED" : "DENIED") + " requester=" + requesterId
                + " patient=" + patientId + " category=" + category);
        return decision;
    }

    private boolean decide(String requesterId, String patientId, HealthCategory category) {
        if (requesterId.equals(patientId)) {
            return true; // the patient is the master controller of the own PHR
        }
        Map<String, Set<HealthCategory>> grants = personalGrants.get(patientId);
        Set<HealthCategory> granted = grants == null ? null : grants.get(requesterId);
        if (granted == null || !granted.contains(category)) {
            return false; // no personal grant: denied by default
        }
        UserAccount requester = repository.findUser(requesterId);
        if (requester != null && requester.getRole() == AuthorRole.HEALTH_PROFESSIONAL
                && requester.getOrganizationId() != null && requester.getProfileName() != null) {
            Map<String, Set<HealthCategory>> profiles = organizationalProfiles.get(requester.getOrganizationId());
            Set<HealthCategory> scope = profiles == null ? null : profiles.get(requester.getProfileName());
            return scope != null && scope.contains(category); // intersection of the two approaches
        }
        return true; // third-party personally authorized by the patient
    }

    public Set<HealthCategory> grantedCategories(String patientId, String granteeId) {
        Map<String, Set<HealthCategory>> grants = personalGrants.get(patientId);
        Set<HealthCategory> granted = grants == null ? null : grants.get(granteeId);
        return granted == null ? EnumSet.noneOf(HealthCategory.class) : EnumSet.copyOf(granted);
    }

    public List<String> getAuditLog() {
        synchronized (auditLog) {
            return new ArrayList<>(auditLog);
        }
    }

    private void audit(String entry) {
        auditLog.add(entry);
    }
}
