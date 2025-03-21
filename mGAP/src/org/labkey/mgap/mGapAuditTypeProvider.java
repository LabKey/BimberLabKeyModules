package org.labkey.mgap;

import org.labkey.api.audit.AbstractAuditTypeProvider;
import org.labkey.api.audit.AuditLogService;
import org.labkey.api.audit.AuditTypeEvent;
import org.labkey.api.audit.AuditTypeProvider;
import org.labkey.api.audit.query.AbstractAuditDomainKind;
import org.labkey.api.data.Container;
import org.labkey.api.exp.PropertyDescriptor;
import org.labkey.api.exp.PropertyType;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by bimber on 6/2/2017.
 */
public class mGapAuditTypeProvider extends AbstractAuditTypeProvider implements AuditTypeProvider
{
    public static final String AUDIT_EVENT_TYPE = "MGapAuditEvent";

    public static final String COLUMN_NAME_TYPE = "Type";
    public static final String COLUMN_NAME_VERSION = "ReleaseVersion";

    public mGapAuditTypeProvider()
    {
        
    }

    @Override
    protected AbstractAuditDomainKind getDomainKind()
    {
        return new mGapAuditTypeProvider.AuditDomainKind();
    }

    @Override
    public String getEventName()
    {
        return AUDIT_EVENT_TYPE;
    }

    @Override
    public String getLabel()
    {
        return "mGAP Audit Events";
    }

    @Override
    public String getDescription()
    {
        return "Contains records of downloads and other mGAP events";
    }

    @Override
    public List<FieldKey> getDefaultVisibleColumns()
    {
        return Arrays.asList(
            FieldKey.fromString(COLUMN_NAME_TYPE),
            FieldKey.fromString(COLUMN_NAME_VERSION),
            FieldKey.fromString("Comment"),
            FieldKey.fromString("Created")
        );
    }

    @Override
    public <K extends AuditTypeEvent> Class<K> getEventClass()
    {
        return (Class<K>)mGapAuditTypeProvider.AuditEvent.class;
    }

    public static void addAuditEntry(Container container, User user, String comment, String type, String releaseVersion)
    {
        mGapAuditTypeProvider.AuditEvent event = new mGapAuditTypeProvider.AuditEvent(container, comment);

        event.setType(type);
        event.setReleaseVersion(releaseVersion);

        AuditLogService.get().addEvent(user, event);
    }

    public static class AuditEvent extends AuditTypeEvent
    {
        private String _type;
        private String _releaseVersion;

        public AuditEvent()
        {
            super();
        }

        public AuditEvent(Container container, String comment)
        {
            super(AUDIT_EVENT_TYPE, container, comment);
        }

        public String getType()
        {
            return _type;
        }

        public void setType(String type)
        {
            _type = type;
        }

        public String getReleaseVersion()
        {
            return _releaseVersion;
        }

        public void setReleaseVersion(String releaseVersion)
        {
            _releaseVersion = releaseVersion;
        }
    }

    public static class AuditDomainKind extends AbstractAuditDomainKind
    {
        public static final String NAME = "MGapAuditDomain";
        public static String NAMESPACE_PREFIX = "Audit-" + NAME;

        private final Set<PropertyDescriptor> _fields;

        public AuditDomainKind()
        {
            super(AUDIT_EVENT_TYPE);

            Set<PropertyDescriptor> fields = new LinkedHashSet<>();
            fields.add(createPropertyDescriptor(COLUMN_NAME_TYPE, PropertyType.STRING));
            fields.add(createPropertyDescriptor(COLUMN_NAME_VERSION, PropertyType.DOUBLE));
            _fields = Collections.unmodifiableSet(fields);
        }

        @Override
        public Set<PropertyDescriptor> getProperties()
        {
            return _fields;
        }

        @Override
        protected String getNamespacePrefix()
        {
            return NAMESPACE_PREFIX;
        }

        @Override
        public String getKindName()
        {
            return NAME;
        }
    }
}
