CREATE TABLE mcc.animalRequests (
	rowid int identity(1,1),
	objectid VARCHAR(40),
	lastName VARCHAR(1000),
	firstName VARCHAR(1000),
	middleInitial VARCHAR(10),
	isPrincipalInvestigator BIT,
	institutionName VARCHAR(1000),
	institutionCity VARCHAR(1000),
	institutionState VARCHAR(1000),
	institutionCountry VARCHAR(1000),
	institutionType VARCHAR(50),
	officialLastName VARCHAR(1000),
	officialFirstName VARCHAR(1000),
	officialEmail VARCHAR(1000),
	fundingSource VARCHAR(50),
	experimentalRationale VARCHAR(4000),
	numberOfAnimals SMALLINT,
	otherCharacteristics VARCHAR(4000),
	methodsProposed VARCHAR(4000),
	collaborations VARCHAR(4000),
	isBreedingAnimals BIT,
	ofInterestCenters VARCHAR(4000),
	researchArea VARCHAR(50),
	otherJustification VARCHAR(255),
	existingMarmosetColony VARCHAR(50),
	existingNHPFacilities VARCHAR(50),
	animalWelfare VARCHAR(4000),
	certify VARCHAR(50),
	vetLastName VARCHAR(1000),
	vetFirstName VARCHAR(1000),
	vetEmail VARCHAR(1000),
	iacucApproval VARCHAR(50),
	container ENTITYID,
	created datetime,
	createdby int,
	modified datetime,
	modifiedby int,

	CONSTRAINT PK_animalRequests PRIMARY KEY (rowid)
);


CREATE TABLE mcc.coinvestigators (
	rowid int identity(1,1),
	requestid VARCHAR(40),
	lastName VARCHAR(1000),
	firstName VARCHAR(1000),
	middleInitial VARCHAR(10),
	institutionName VARCHAR(1000),
	container ENTITYID,
	created datetime,
	createdby int,
	modified datetime,
	modifiedby int,

	CONSTRAINT PK_coinvestigators PRIMARY KEY (rowid)
);