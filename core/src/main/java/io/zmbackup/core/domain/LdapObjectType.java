package io.zmbackup.core.domain;

public enum LdapObjectType {
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

    public String objectFilter() {
        return objectFilter;
    }

    public String attributeName() {
        return attributeName;
    }
}
