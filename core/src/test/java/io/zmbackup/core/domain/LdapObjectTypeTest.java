package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LdapObjectTypeTest {

    @Test
    void accountFilterAndAttribute() {
        assertEquals("(objectclass=zimbraAccount)", LdapObjectType.ACCOUNT.objectFilter());
        assertEquals("zimbraMailDeliveryAddress", LdapObjectType.ACCOUNT.attributeName());
    }

    @Test
    void distributionListFilterAndAttribute() {
        assertEquals("(objectclass=zimbraDistributionList)", LdapObjectType.DISTRIBUTION_LIST.objectFilter());
        assertEquals("mail", LdapObjectType.DISTRIBUTION_LIST.attributeName());
    }

    @Test
    void aliasFilterAndAttribute() {
        assertEquals("(objectclass=zimbraAlias)", LdapObjectType.ALIAS.objectFilter());
        assertEquals("uid", LdapObjectType.ALIAS.attributeName());
    }

    @Test
    void signatureFilterAndAttribute() {
        assertEquals("(objectclass=zimbraSignature)", LdapObjectType.SIGNATURE.objectFilter());
        assertEquals("zimbraSignatureName", LdapObjectType.SIGNATURE.attributeName());
    }

    @Test
    void domainFilterAndAttribute() {
        assertEquals("(objectclass=zimbraDomain)", LdapObjectType.DOMAIN.objectFilter());
        assertEquals("zimbraDomainName", LdapObjectType.DOMAIN.attributeName());
    }
}
