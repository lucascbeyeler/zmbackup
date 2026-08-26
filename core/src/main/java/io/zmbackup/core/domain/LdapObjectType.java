package io.zmbackup.core.domain;

/**
 * A class of Zimbra LDAP object that can be backed up, carrying the search filter and the
 * identifying attribute the bash tool used for each (the {@code *OBJECT}/{@code *FILTER}
 * constants in {@code MiscAction.sh}).
 */
public enum LdapObjectType {
    // The bash tool narrows this to active-only accounts when BACKUP_INACTIVE_ACCOUNTS is
    // false; that toggle is an app-level query concern, not part of the base object filter.
    ACCOUNT("(objectclass=zimbraAccount)", "zimbraMailDeliveryAddress"),
    DISTRIBUTION_LIST("(objectclass=zimbraDistributionList)", "mail"),
    ALIAS("(objectclass=zimbraAlias)", "uid"),
    SIGNATURE("(objectclass=zimbraSignature)", "zimbraSignatureName"),
    DOMAIN("(objectclass=zimbraDomain)", "zimbraDomainName");

    private final String objectFilter;
    private final String attributeName;

    LdapObjectType(String objectFilter, String attributeName) {
        this.objectFilter = objectFilter;
        this.attributeName = attributeName;
    }

    /** The LDAP search filter used to enumerate objects of this type. */
    public String objectFilter() {
        return objectFilter;
    }

    /** The LDAP attribute used to identify each object of this type. */
    public String attributeName() {
        return attributeName;
    }
}
