package eu.efa.keycloak.mapper;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.UserModelDelegate;
import org.keycloak.models.utils.reflection.Property;
import org.keycloak.models.utils.reflection.PropertyCriteria;
import org.keycloak.models.utils.reflection.PropertyQueries;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.idm.query.Condition;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.storage.ldap.mappers.AbstractLDAPStorageMapper;
import org.keycloak.storage.ldap.mappers.TxAwareLDAPUserModelDelegate;

import java.lang.reflect.Method;
import java.util.*;

public class UserAttributeValueLDAPMapper extends AbstractLDAPStorageMapper {

    private static final Logger logger = Logger.getLogger(UserAttributeValueLDAPMapper.class);

    private static final Map<String, Property<Object>> userModelProperties;

    static {
        Map<String, Property<Object>> userModelProps = PropertyQueries.createQuery(UserModel.class).addCriteria(new PropertyCriteria() {

            @Override
            public boolean methodMatches(Method m) {
                if ((m.getName().startsWith("get") || m.getName().startsWith("is")) && m.getParameterTypes().length > 0) {
                    return false;
                }

                return true;
            }

        }).getResultList();

        // Convert to be keyed by lower-cased attribute names
        userModelProperties = new HashMap<>();
        for (Map.Entry<String, Property<Object>> entry : userModelProps.entrySet()) {
            userModelProperties.put(entry.getKey().toLowerCase(), entry.getValue());
        }
    }

    public static final String USER_MODEL_ATTRIBUTE = "user.model.attribute";
    public static final String LDAP_ATTRIBUTE = "ldap.attribute";
    public static final String USER_MODEL_VALUE_ATTRIBUTE = "user.model.attribute.value";
    public static final String LDAP_VALUE_ATTRIBUTE = "ldap.attribute.value";
    public static final String READ_ONLY = "read.only";
    public static final String ALWAYS_READ_VALUE_FROM_LDAP = "always.read.value.from.ldap";
    public static final String IS_MANDATORY_IN_LDAP = "is.mandatory.in.ldap";
    public static final String IS_BINARY_ATTRIBUTE = "is.binary.attribute";

    public UserAttributeValueLDAPMapper(ComponentModel mapperModel, LDAPStorageProvider ldapProvider) {
        super(mapperModel, ldapProvider);
    }

    @Override
    public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        String userModelAttrName = getUserModelAttribute();
        String ldapAttrName = getLdapAttributeName();

        List<String> userModelAttrValueList = getUserModelAttributeValue();
        List<String> ldapAttrValueList = getLdapAttributeValue();

        // We won't update binary attributes to Keycloak DB. They might be too big
        if (isBinaryAttribute()) {
            return;
        }

        Property<Object> userModelProperty = userModelProperties.get(userModelAttrName.toLowerCase());

        if (userModelProperty != null) {

            // we have java property on UserModel
            String ldapAttrValue = ldapUser.getAttributeAsString(ldapAttrName);

            if (ldapAttrValueList.contains(ldapAttrValue)) {
                ldapAttrValue = userModelAttrValueList.get(ldapAttrValueList.indexOf(ldapAttrValue));
            }

            checkDuplicateEmail(userModelAttrName, ldapAttrValue, realm, ldapProvider.getSession(), user);

            setPropertyOnUserModel(userModelProperty, user, ldapAttrValue);
        } else {

            // we don't have java property. Let's set attribute
            Set<String> ldapAttrValue = ldapUser.getAttributeAsSet(ldapAttrName);
            if (ldapAttrValue != null) {
                user.setAttribute(userModelAttrName, new ArrayList<>(ldapAttrValue));
            } else {
                user.removeAttribute(userModelAttrName);
            }
        }
    }

    @Override
    public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {
        String userModelAttrName = getUserModelAttribute();
        String ldapAttrName = getLdapAttributeName();
        boolean isMandatoryInLdap = parseBooleanParameter(mapperModel, IS_MANDATORY_IN_LDAP);

        Property<Object> userModelProperty = userModelProperties.get(userModelAttrName.toLowerCase());

        if (userModelProperty != null) {

            // we have java property on UserModel. Assuming we support just properties of simple types
            Object attrValue = userModelProperty.getValue(localUser);

            if (attrValue == null) {
                if (isMandatoryInLdap) {
                    ldapUser.setSingleAttribute(ldapAttrName, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                } else {
                    ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<String>());
                }
            } else {
                ldapUser.setSingleAttribute(ldapAttrName, attrValue.toString());
            }
        } else {

            // we don't have java property. Let's set attribute
            List<String> attrValues = localUser.getAttribute(userModelAttrName);

            if (attrValues.size() == 0) {
                if (isMandatoryInLdap) {
                    ldapUser.setSingleAttribute(ldapAttrName, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                } else {
                    ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<String>());
                }
            } else {
                ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<>(attrValues));
            }
        }

        if (isReadOnly()) {
            ldapUser.addReadOnlyAttributeName(ldapAttrName);
        }
    }

    // throw ModelDuplicateException if there is different user in model with same email
    protected void checkDuplicateEmail(String userModelAttrName, String email, RealmModel realm, KeycloakSession session, UserModel user) {
        if (email == null || realm.isDuplicateEmailsAllowed()) return;
        if (UserModel.EMAIL.equalsIgnoreCase(userModelAttrName)) {
            // lowercase before search
            email = KeycloakModelUtils.toLowerCaseSafe(email);

            UserModel that = session.userLocalStorage().getUserByEmail(email, realm);
            if (that != null && !that.getId().equals(user.getId())) {
                session.getTransactionManager().setRollbackOnly();
                String exceptionMessage = String.format("Can't import user '%s' from LDAP because email '%s' already exists in Keycloak. Existing user with this email is '%s'", user.getUsername(), email, that.getUsername());
                throw new ModelDuplicateException(exceptionMessage, UserModel.EMAIL);
            }
        }
    }

    protected void checkDuplicateUsername(String userModelAttrName, String username, RealmModel realm, KeycloakSession session, UserModel user) {
        // only if working in USERNAME attribute
        if (UserModel.USERNAME.equalsIgnoreCase(userModelAttrName)) {
            if (username == null || username.isEmpty()) {
                throw new ModelException("Cannot set an empty username");
            }
            boolean usernameChanged = !username.equals(user.getUsername());
            if (realm.isEditUsernameAllowed() && usernameChanged) {
                UserModel that = session.users().getUserByUsername(username, realm);
                if (that != null && !that.getId().equals(user.getId())) {
                    throw new ModelDuplicateException(
                            String.format("Cannot change the username to '%s' because the username already exists in keycloak", username),
                            UserModel.USERNAME);
                }
            } else if (usernameChanged) {
                throw new ModelException("Cannot change username if the realm is not configured to allow edit the usernames");
            }
        }
    }

    @Override
    public UserModel proxy(final LDAPObject ldapUser, UserModel delegate, RealmModel realm) {
        final String userModelAttrName = getUserModelAttribute();
        final String ldapAttrName = getLdapAttributeName();
        boolean isAlwaysReadValueFromLDAP = parseBooleanParameter(mapperModel, ALWAYS_READ_VALUE_FROM_LDAP);
        final boolean isMandatoryInLdap = parseBooleanParameter(mapperModel, IS_MANDATORY_IN_LDAP);
        final boolean isBinaryAttribute = parseBooleanParameter(mapperModel, IS_BINARY_ATTRIBUTE);

        // For writable mode, we want to propagate writing of attribute to LDAP as well
        if (ldapProvider.getEditMode() == UserStorageProvider.EditMode.WRITABLE && !isReadOnly()) {

            delegate = new TxAwareLDAPUserModelDelegate(delegate, ldapProvider, ldapUser) {

                @Override
                public void setSingleAttribute(String name, String value) {
                    if (setLDAPAttribute(name, value)) {
                        super.setSingleAttribute(name, value);
                    }
                }

                @Override
                public void setAttribute(String name, List<String> values) {
                    if (setLDAPAttribute(name, values)) {
                        super.setAttribute(name, values);
                    }
                }

                @Override
                public void removeAttribute(String name) {
                    if ( setLDAPAttribute(name, null)) {
                        super.removeAttribute(name);
                    }
                }

                @Override
                public void setUsername(String username) {
                    checkDuplicateUsername(userModelAttrName, username, realm, ldapProvider.getSession(), this);
                    setLDAPAttribute(UserModel.USERNAME, username);
                    super.setUsername(username);
                }

                @Override
                public void setEmail(String email) {
                    checkDuplicateEmail(userModelAttrName, email, realm, ldapProvider.getSession(), this);

                    setLDAPAttribute(UserModel.EMAIL, email);
                    super.setEmail(email);
                }

                @Override
                public void setLastName(String lastName) {
                    setLDAPAttribute(UserModel.LAST_NAME, lastName);
                    super.setLastName(lastName);
                }

                @Override
                public void setFirstName(String firstName) {
                    setLDAPAttribute(UserModel.FIRST_NAME, firstName);
                    super.setFirstName(firstName);
                }

                protected boolean setLDAPAttribute(String modelAttrName, Object value) {
                    if (modelAttrName.equalsIgnoreCase(userModelAttrName)) {
                        if (UserAttributeValueLDAPMapper.logger.isTraceEnabled()) {
                            UserAttributeValueLDAPMapper.logger.tracef("Pushing user attribute to LDAP. username: %s, Model attribute name: %s, LDAP attribute name: %s, Attribute value: %s", getUsername(), modelAttrName, ldapAttrName, value);
                        }

                        ensureTransactionStarted();

                        if (value == null) {
                            if (isMandatoryInLdap) {
                                ldapUser.setSingleAttribute(ldapAttrName, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                            } else {
                                ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<String>());
                            }
                        } else if (value instanceof String) {
                            ldapUser.setSingleAttribute(ldapAttrName, (String) value);
                        } else {
                            List<String> asList = (List<String>) value;
                            if (asList.isEmpty() && isMandatoryInLdap) {
                                ldapUser.setSingleAttribute(ldapAttrName, LDAPConstants.EMPTY_ATTRIBUTE_VALUE);
                            } else {
                                ldapUser.setAttribute(ldapAttrName, new LinkedHashSet<>(asList));
                            }
                        }

                        if (isBinaryAttribute) {
                            UserAttributeValueLDAPMapper.logger.debugf("Skip writing model attribute '%s' to DB for user '%s' as it is mapped to binary LDAP attribute.", userModelAttrName, getUsername());
                            return false;
                        } else {
                            return true;
                        }
                    }

                    return true;
                }

            };

        } else if (isBinaryAttribute) {

            delegate = new UserModelDelegate(delegate) {

                @Override
                public void setSingleAttribute(String name, String value) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        logSkipDBWrite();
                    } else {
                        super.setSingleAttribute(name, value);
                    }
                }

                @Override
                public void setAttribute(String name, List<String> values) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        logSkipDBWrite();
                    } else {
                        super.setAttribute(name, values);
                    }
                }

                @Override
                public void removeAttribute(String name) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        logSkipDBWrite();
                    } else {
                        super.removeAttribute(name);
                    }
                }

                private void logSkipDBWrite() {
                    logger.debugf("Skip writing model attribute '%s' to DB for user '%s' as it is mapped to binary LDAP attribute", userModelAttrName, getUsername());
                }

            };

        }

        // We prefer to read attribute value from LDAP instead of from local Keycloak DB
        if (isAlwaysReadValueFromLDAP) {

            delegate = new UserModelDelegate(delegate) {

                @Override
                public String getFirstAttribute(String name) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getFirstAttribute(name);
                    }
                }

                @Override
                public List<String> getAttribute(String name) {
                    if (name.equalsIgnoreCase(userModelAttrName)) {
                        Collection<String> ldapAttrValue = ldapUser.getAttributeAsSet(ldapAttrName);
                        if (ldapAttrValue == null) {
                            return Collections.emptyList();
                        } else {
                            return new ArrayList<>(ldapAttrValue);
                        }
                    } else {
                        return super.getAttribute(name);
                    }
                }

                @Override
                public Map<String, List<String>> getAttributes() {
                    Map<String, List<String>> attrs = new HashMap<>(super.getAttributes());

                    // Ignore UserModel properties
                    if (userModelProperties.get(userModelAttrName.toLowerCase()) != null) {
                        return attrs;
                    }

                    Set<String> allLdapAttrValues = ldapUser.getAttributeAsSet(ldapAttrName);
                    if (allLdapAttrValues != null) {
                        attrs.put(userModelAttrName, new ArrayList<>(allLdapAttrValues));
                    }
                    return attrs;
                }

                @Override
                public String getEmail() {
                    if (UserModel.EMAIL.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getEmail();
                    }
                }

                @Override
                public String getLastName() {
                    if (UserModel.LAST_NAME.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getLastName();
                    }
                }

                @Override
                public String getFirstName() {
                    if (UserModel.FIRST_NAME.equalsIgnoreCase(userModelAttrName)) {
                        return ldapUser.getAttributeAsString(ldapAttrName);
                    } else {
                        return super.getFirstName();
                    }
                }

            };
        }

        return delegate;
    }

    @Override
    public void beforeLDAPQuery(LDAPQuery query) {
        String userModelAttrName = getUserModelAttribute();
        String ldapAttrName = getLdapAttributeName();

        // Add mapped attribute to returning ldap attributes
        query.addReturningLdapAttribute(ldapAttrName);
        if (isReadOnly()) {
            query.addReturningReadOnlyLdapAttribute(ldapAttrName);
        }

        // Change conditions and use ldapAttribute instead of userModel
        for (Condition condition : query.getConditions()) {
            condition.updateParameterName(userModelAttrName, ldapAttrName);
            String parameterName = condition.getParameterName();
            if (parameterName != null && (parameterName.equalsIgnoreCase(userModelAttrName) || parameterName.equalsIgnoreCase(ldapAttrName))) {
                condition.setBinary(isBinaryAttribute());
            }
        }
    }

    private String getUserModelAttribute() {
        return mapperModel.getConfig().getFirst(USER_MODEL_ATTRIBUTE);
    }

    private List<String> getUserModelAttributeValue() {
        return mapperModel.getConfig().getList(USER_MODEL_VALUE_ATTRIBUTE);
    }

    List<String> getLdapAttributeValue() {
        return mapperModel.getConfig().getList(LDAP_VALUE_ATTRIBUTE);
    }

    String getLdapAttributeName() {
        return mapperModel.getConfig().getFirst(LDAP_ATTRIBUTE);
    }

    private boolean isBinaryAttribute() {
        return mapperModel.get(IS_BINARY_ATTRIBUTE, false);
    }

    private boolean isReadOnly() {
        return parseBooleanParameter(mapperModel, READ_ONLY);
    }


    protected void setPropertyOnUserModel(Property<Object> userModelProperty, UserModel user, String ldapAttrValue) {
        if (ldapAttrValue == null) {
            userModelProperty.setValue(user, null);
        } else {
            Class<Object> clazz = userModelProperty.getJavaClass();

            if (String.class.equals(clazz)) {
                userModelProperty.setValue(user, ldapAttrValue);
            } else if (Boolean.class.equals(clazz) || boolean.class.equals(clazz)) {
                Boolean boolVal = Boolean.valueOf(ldapAttrValue);
                userModelProperty.setValue(user, boolVal);
            } else {
                logger.warnf("Don't know how to set the property '%s' on user '%s' . Value of LDAP attribute is '%s' ", userModelProperty.getName(), user.getUsername(), ldapAttrValue.toString());
            }
        }
    }
}