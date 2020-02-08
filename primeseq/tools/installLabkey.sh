#!/bin/sh
#
# This script is designed to upgrade LabKey on this server
# usage: ./installLabKey.sh ${distribution}
#

set -x

labkey_home=/usr/local/labkey
cd /usr/local/src

#NOTE: corresponding changes must be made in javaWrapper.sh
MAJOR=19
MINOR_FULL="3.4"
MINOR_SHORT=3
BRANCH=LabKey_Discvr_Discvr${MAJOR}${MINOR_SHORT}_Premuim_Installers
TOMCAT_HOME=/usr/share/tomcat
TEAMCITY_USERNAME=username
MODULE_DIST_NAME=prime-seq-modules

ARTIFACT=LabKey${MAJOR}.${MINOR_FULL}
PREMIUM=premium-${MAJOR}.${MINOR_SHORT}.module
DATAINTEGRATION=dataintegration-${MAJOR}.${MINOR_SHORT}.module

isGzZip() {
	RET=`file $1 | grep -E 'gzip compressed|Zip archive data' | wc -l`
	if [ $RET == 0 ];then
		echo "Not GZIP!"
		exit 1
	else
		echo "Is GZIP!"
	fi
}

#first download
DATE=$(date +"%Y%m%d%H%M")
MODULE_ZIP=${ARTIFACT}-ExtraModules-${DATE}.zip
rm -Rf $MODULE_ZIP
wget -O $MODULE_ZIP https://${TEAMCITY_USERNAME}@teamcity.labkey.org/repository/download/${BRANCH}/.lastSuccessful/${MODULE_DIST_NAME}/${ARTIFACT}-{build.number}-ExtraModules.zip
isGzZip $MODULE_ZIP

GZ=${ARTIFACT}-${DATE}-discvr-bin.tar.gz
rm -Rf $GZ
wget -O $GZ https://${TEAMCITY_USERNAME}@teamcity.labkey.org/repository/download/${BRANCH}/.lastSuccessful/discvr/${ARTIFACT}-{build.number}-discvr-bin.tar.gz
isGzZip $GZ

#extract, find name
tar -xf $GZ
DIR=$(ls -tr | grep "^${ARTIFACT}*" | grep 'discvr-bin$' | tail -n -1)
echo "DIR: $DIR"
BASENAME=$(echo ${DIR} | sed 's/-discvr-bin//')
mv $GZ ./${BASENAME}-discvr-bin.tar.gz
mv $MODULE_ZIP ./${BASENAME}-ExtraModules.zip
GZ=${BASENAME}-discvr-bin.tar.gz
MODULE_ZIP=${BASENAME}-ExtraModules.zip

systemctl stop labkey.service

#extra modules first
rm -Rf ${labkey_home}/externalModules
mkdir -p ${labkey_home}/externalModules
rm -Rf modules_unzip
unzip $MODULE_ZIP -d ./modules_unzip
MODULE_DIR=$(ls ./modules_unzip | tail -n -1)
echo $MODULE_DIR
cp ./modules_unzip/${MODULE_DIR}/modules/*.module ${labkey_home}/externalModules
rm -Rf ./modules_unzip

#premium
if [ -e $PREMIUM ];then
        cp $PREMIUM ${labkey_home}/externalModules
fi

#DataIntegration
if [ -e $DATAINTEGRATION ];then
        cp $DATAINTEGRATION ${labkey_home}/externalModules
fi

#main server
echo "Installing LabKey using: $GZ"
cd $DIR
./manual-upgrade.sh -u labkey -c /usr/local/tomcat -l $labkey_home --noPrompt
cd ../
systemctl start labkey.service

# clean up
echo "Removing folder: $DIR"
rm -Rf $DIR

echo "cleaning up installers, leaving 5 most recent"
ls -tr | grep "^${ARTIFACT}.*\.gz$" | head -n -5 | xargs rm

echo "cleaning up ZIP, leaving 5 most recent"
ls -tr | grep "^${ARTIFACT}.*\.zip$" | head -n -5 | xargs rm