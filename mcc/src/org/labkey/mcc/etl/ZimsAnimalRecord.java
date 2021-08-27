package org.labkey.mcc.etl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.util.GUID;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@JacksonXmlRootElement(localName = "Animal")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZimsAnimalRecord
{
    @JacksonXmlProperty(localName = "BasicInfo")
    public BasicInfo _basicInfo;

    @JacksonXmlElementWrapper(localName = "WeightList")
    @JacksonXmlProperty(localName = "Weight")
    public List<WeightRecord> _weights;

    @JacksonXmlElementWrapper(localName = "IdentifierList")
    @JacksonXmlProperty(localName = "Identifier")
    public List<Identifier> _identifiers;

    @JacksonXmlProperty(localName = "ParentInfoList")
    public ParentInfoList _parentInfoList;

    public ZimsAnimalRecord()
    {

    }

    public List<Identifier> getIdentifiers()
    {
        return _identifiers;
    }

    public void setIdentifiers(List<Identifier> identifiers)
    {
        _identifiers = identifiers;
    }

    public ParentInfoList getParentInfoList()
    {
        return _parentInfoList;
    }

    public void setParentInfoList(ParentInfoList parentInfoList)
    {
        _parentInfoList = parentInfoList;
    }

    public List<WeightRecord> getWeights()
    {
        return _weights;
    }

    public void setWeights(List<WeightRecord> weights)
    {
        _weights = weights;
    }

    public BasicInfo getBasicInfo()
    {
        return _basicInfo;
    }

    public void setBasicInfo(BasicInfo basicInfo)
    {
        _basicInfo = basicInfo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParentInfoList
    {
        public ParentInfoList()
        {

        }

        @JacksonXmlProperty(localName = "AnimalType")
        private String _animalType;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "ParentInfo")
        private List<ParentInfo> _parentInfo;

        public String getAnimalType()
        {
            return _animalType;
        }

        public void setAnimalType(String animalType)
        {
            _animalType = animalType;
        }

        public List<ParentInfo> getParentInfo()
        {
            return _parentInfo;
        }

        public void setParentInfo(List<ParentInfo> parentInfo)
        {
            _parentInfo = parentInfo;
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BasicInfo
    {
        @JacksonXmlProperty(localName = "GAN")
        private String _gan;

        @JacksonXmlProperty(localName = "AccessionCode")
        private String _accessionCode;

        @JacksonXmlProperty(localName = "AnimalType")
        private String _animalType;

        @JacksonXmlProperty(localName = "GlobalSexType")
        private String _globalSexType;

        @JacksonXmlProperty(localName = "GlobalBirthInfo")
        private GlobalBirthInfo _GlobalBirthInfo;

        @JacksonXmlProperty(localName = "AgeTracking")
        public AgeTracking _ageTracking;

        @JacksonXmlProperty(localName = "ObsoleteFlag")
        @JsonDeserialize(using = CaseInsensitiveBoolean.class)
        private boolean _obsoleteFlag;

        @JacksonXmlProperty(localName = "TaxonomyScientificName")
        private String _taxonomyScientificName;

        @JacksonXmlProperty(localName = "TaxonomyCommonName")
        private String _taxonomyCommonName;

        @JacksonXmlProperty(localName = "AnimalStatus")
        private String _animalStatus;

        @JacksonXmlProperty(localName = "ColonyFlag")
        @JsonDeserialize(using = CaseInsensitiveBoolean.class)
        private boolean _colonyFlag = false;

        @JacksonXmlProperty(localName = "WildConceived")
        private String _wildConceived;

        @JacksonXmlProperty(localName = "InstitutionCollection")
        private String _institutionCollection;

        @JacksonXmlProperty(localName = "MyLocalID")
        private String _myLocalID;

        @JacksonXmlProperty(localName = "HouseName")
        private String _houseName;

        public String getGan()
        {
            return _gan;
        }

        public void setGan(String gan)
        {
            _gan = gan;
        }

        public AgeTracking getAgeTracking()
        {
            return _ageTracking;
        }

        public void setAgeTracking(AgeTracking ageTracking)
        {
            _ageTracking = ageTracking;
        }

        public String getAccessionCode()
        {
            return _accessionCode;
        }

        public void setAccessionCode(String accessionCode)
        {
            _accessionCode = accessionCode;
        }

        public String getAnimalType()
        {
            return _animalType;
        }

        public void setAnimalType(String animalType)
        {
            _animalType = animalType;
        }

        public String getGlobalSexType()
        {
            return _globalSexType;
        }

        public void setGlobalSexType(String globalSexType)
        {
            _globalSexType = globalSexType;
        }

        public GlobalBirthInfo getGlobalBirthInfo()
        {
            return _GlobalBirthInfo;
        }

        public void setGlobalBirthInfo(GlobalBirthInfo globalBirthInfo)
        {
            _GlobalBirthInfo = globalBirthInfo;
        }

        public boolean isObsoleteFlag()
        {
            return _obsoleteFlag;
        }

        public void setObsoleteFlag(boolean obsoleteFlag)
        {
            _obsoleteFlag = obsoleteFlag;
        }

        public String getTaxonomyScientificName()
        {
            return _taxonomyScientificName;
        }

        public void setTaxonomyScientificName(String taxonomyScientificName)
        {
            _taxonomyScientificName = taxonomyScientificName;
        }

        public String getTaxonomyCommonName()
        {
            return _taxonomyCommonName;
        }

        public void setTaxonomyCommonName(String taxonomyCommonName)
        {
            _taxonomyCommonName = taxonomyCommonName;
        }

        public String getAnimalStatus()
        {
            return _animalStatus;
        }

        public void setAnimalStatus(String animalStatus)
        {
            _animalStatus = animalStatus;
        }

        public boolean isColonyFlag()
        {
            return _colonyFlag;
        }

        public void setColonyFlag(boolean colonyFlag)
        {
            _colonyFlag = colonyFlag;
        }

        public String getWildConceived()
        {
            return _wildConceived;
        }

        public void setWildConceived(String wildConceived)
        {
            _wildConceived = wildConceived;
        }

        public String getInstitutionCollection()
        {
            return _institutionCollection;
        }

        public void setInstitutionCollection(String institutionCollection)
        {
            _institutionCollection = institutionCollection;
        }

        public String getMyLocalID()
        {
            return _myLocalID;
        }

        public void setMyLocalID(String myLocalID)
        {
            _myLocalID = myLocalID;
        }

        public String getHouseName()
        {
            return _houseName;
        }

        public void setHouseName(String houseName)
        {
            _houseName = houseName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgeTracking
    {
        @JacksonXmlProperty(localName = "Date")
        private Date _date;

        @JacksonXmlProperty(localName = "HintText")
        private String _hintText;

        public Date getDate()
        {
            return _date;
        }

        public void setDate(Date date)
        {
            _date = date;
        }

        public String getHintText()
        {
            return _hintText;
        }

        public void setHintText(String hintText)
        {
            _hintText = hintText;
        }

        public boolean isDeathDate()
        {
            return "at the time of death".equalsIgnoreCase(_hintText);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalBirthInfo
    {
        public GlobalBirthInfo()
        {

        }

        @JacksonXmlProperty(localName = "Date")
        private Date _date;

        @JacksonXmlProperty(localName = "EstimateFlag")
        private String _estimateFlag;

        @JacksonXmlProperty(localName = "BirthType")
        private String _birthType;

        @JacksonXmlProperty(localName = "BirthLocation")
        private String _birthLocation;

        public Date getDate()
        {
            return _date;
        }

        public void setDate(Date date)
        {
            _date = date;
        }

        public String getEstimateFlag()
        {
            return _estimateFlag;
        }

        public void setEstimateFlag(String estimateFlag)
        {
            _estimateFlag = estimateFlag;
        }

        public String getBirthType()
        {
            return _birthType;
        }

        public void setBirthType(String birthType)
        {
            _birthType = birthType;
        }

        public String getBirthLocation()
        {
            return _birthLocation;
        }

        public void setBirthLocation(String birthLocation)
        {
            _birthLocation = birthLocation;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeightRecord
    {
        public WeightRecord()
        {

        }

        @JacksonXmlProperty(localName = "MeasurementDateTime")
        public Date _measurementDateTime;

        @JacksonXmlProperty(localName = "EstimateFlag")
        @JsonDeserialize(using = CaseInsensitiveBoolean.class)
        public boolean _estimateFlag = false;

        @JacksonXmlProperty(localName = "MedicalRecordFlag")
        @JsonDeserialize(using = CaseInsensitiveBoolean.class)
        public boolean _medicalRecordFlag = true;

        @JacksonXmlProperty(localName = "ProvisionalFlag")
        @JsonDeserialize(using = CaseInsensitiveBoolean.class)
        public boolean _provisionalFlag = false;

        @JacksonXmlProperty(localName = "UOMAbbr")
        public String _uOMAbbr;

        @JacksonXmlProperty(localName = "MeasurementValue")
        public Double _measurementValue;

        public Double getMeasurementValue()
        {
            return _measurementValue;
        }

        public void setMeasurementValue(Double measurementValue)
        {
            _measurementValue = measurementValue;
        }

        public Date getMeasurementDateTime()
        {
            return _measurementDateTime;
        }

        public void setMeasurementDateTime(Date measurementDateTime)
        {
            _measurementDateTime = measurementDateTime;
        }

        public boolean getEstimateFlag()
        {
            return _estimateFlag;
        }

        public void setEstimateFlag(boolean estimateFlag)
        {
            _estimateFlag = estimateFlag;
        }

        public boolean getMedicalRecordFlag()
        {
            return _medicalRecordFlag;
        }

        public void setMedicalRecordFlag(boolean medicalRecordFlag)
        {
            _medicalRecordFlag = medicalRecordFlag;
        }

        public boolean getProvisionalFlag()
        {
            return _provisionalFlag;
        }

        public void setProvisionalFlag(boolean provisionalFlag)
        {
            _provisionalFlag = provisionalFlag;
        }

        public String getuOMAbbr()
        {
            return _uOMAbbr;
        }

        public void setuOMAbbr(String uOMAbbr)
        {
            _uOMAbbr = uOMAbbr;
        }

        @JsonIgnore
        public boolean shouldInclude()
        {
            return !(_provisionalFlag || !_medicalRecordFlag || _estimateFlag);
        }
    }

    @JsonIgnore
    public String getLocalId()
    {
        if (_identifiers == null)
        {
            return null;
        }

        for (Identifier i : _identifiers)
        {
            if ("Local ID".equalsIgnoreCase(i.getIdentifierType()))
            {
                return i.getIdentifier();
            }
        }

        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identifier
    {
        public Identifier()
        {

        }

        @JacksonXmlProperty(localName = "RecordID", isAttribute = true)
        public String _recordId;

        @JacksonXmlProperty(localName = "IdentifierType")
        public String _identifierType;

        @JacksonXmlProperty(localName = "Identifier")
        public String _identifier;

        public String getRecordId()
        {
            return _recordId;
        }

        public void setRecordId(String recordId)
        {
            _recordId = recordId;
        }

        public String getIdentifierType()
        {
            return _identifierType;
        }

        public void setIdentifierType(String identifierType)
        {
            _identifierType = identifierType;
        }

        public String getIdentifier()
        {
            return _identifier;
        }

        public void setIdentifier(String identifier)
        {
            _identifier = identifier;
        }
    }

    public String getParentOfType(String type)
    {
        if (getParentInfoList() == null || getParentInfoList().getParentInfo() == null)
        {
            return null;
        }

        for (ParentInfo pi : getParentInfoList().getParentInfo())
        {
            if (type.equalsIgnoreCase(pi.getParentType()))
            {
                return pi.getParentAnimalGAN();
            }
        }

        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParentInfo
    {
        public ParentInfo()
        {

        }

        @JacksonXmlProperty(localName = "ParentType")
        String _parentType;

        @JacksonXmlProperty(localName = "PercentageProbabilityEntered")
        Double _percentageProbabilityEntered;

        @JacksonXmlProperty(localName = "ParentAnimalGAN")
        String _parentAnimalGAN;

        public String getParentType()
        {
            return _parentType;
        }

        public void setParentType(String parentType)
        {
            _parentType = parentType;
        }

        public Double getPercentageProbabilityEntered()
        {
            return _percentageProbabilityEntered;
        }

        public void setPercentageProbabilityEntered(Double percentageProbabilityEntered)
        {
            _percentageProbabilityEntered = percentageProbabilityEntered;
        }

        public String getParentAnimalGAN()
        {
            return _parentAnimalGAN;
        }

        public void setParentAnimalGAN(String parentAnimalGAN)
        {
            _parentAnimalGAN = parentAnimalGAN;
        }
    }


//                RearingList
//        OwnershipHistoryList
//                AnimalVisitList
//        AnimalKeyInfoHistoryList

//        AnimalEventLocationList
//                ObservationList
//        NoteList
//                ClinicalNoteList
//        PathologyList
//                PrescriptionList
//        SampleList
//                TestAndResultList
//        TransactionList


    public static class CaseInsensitiveBoolean extends StdDeserializer<Boolean>
    {
        public CaseInsensitiveBoolean()
        {
            super(Boolean.class);
        }

        public CaseInsensitiveBoolean(Class<?> vc)
        {
            super(vc);
        }

        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
        {
            String val = p.getText();
            return val == null || val.isEmpty() ? null : Boolean.parseBoolean(val.toLowerCase());
        }
    }

    public Map<String, Object> toDemographicsRecord()
    {
        if (getBasicInfo() == null)
        {
            return null;
        }

        Map<String, Object> map = new CaseInsensitiveHashMap<>();
        if (getLocalId() == null)
        {
            throw new IllegalStateException("Missing localId");
        }
        map.put("Id", getLocalId());

        if (getBasicInfo().getGlobalBirthInfo().getDate() == null)
        {
            throw new IllegalStateException("Missing birth: " + getLocalId());
        }

        map.put("date", validateDate(getBasicInfo().getGlobalBirthInfo().getDate()));
        map.put("birth", getBasicInfo().getGlobalBirthInfo().getDate());
        if (getBasicInfo().getGlobalBirthInfo().getDate() == null)
        {
            throw new IllegalStateException("Missing birth: " + getLocalId());
        }

        map.put("species", getBasicInfo().getTaxonomyCommonName());
        map.put("gender", getBasicInfo().getGlobalSexType());

        if (getBasicInfo().getAgeTracking() != null && getBasicInfo().getAgeTracking().isDeathDate())
        {
            map.put("death", validateDate(getBasicInfo().getAgeTracking().getDate()));
        }

        map.put("status", getBasicInfo().getAnimalStatus());
        if (map.get("death") != null && !"dead".equalsIgnoreCase(getBasicInfo().getAnimalStatus()))
        {
            throw new IllegalStateException("Animal has death date but status is not dead: " + getLocalId());
        }

        if ("dead".equalsIgnoreCase(getBasicInfo().getAnimalStatus()) && map.get("death") == null)
        {
            throw new IllegalStateException("Animal has status of dead but lacks date: " + getLocalId());
        }

        if (getParentInfoList() != null)
        {
            map.put("dam", getParentOfType("Dam"));
            map.put("sire", getParentOfType("Sire"));
        }

        map.put("QCStateLabel", "Completed");

        //TODO: calculated_status
        //sourceColony
        //currentColony

        map.put("objectid", new GUID().toString().toUpperCase());

        return map;
    }

    private static final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    private Date validateDate(Date date)
    {
        try
        {
            final Date minDate = _dateFormat.parse("1950-01-01");
            if (date.before(minDate))
            {
                throw new IllegalStateException("Improper date, was: " + date);
            }

            if (date.after(new Date()))
            {
                throw new IllegalStateException("Improper date, was: " + date);
            }

            return date;
        }
        catch (ParseException e)
        {
            throw new IllegalStateException(e);
        }
    }

    public static ZimsAnimalRecord processAnimal(File xml, Logger log) throws PipelineJobException
    {
        log.info("Processing file: " + xml.getName());
        try
        {
            XmlMapper mapper = new XmlMapper();
            return mapper.readValue(xml, ZimsAnimalRecord.class);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }
}
