var console = require("console");
var LABKEY = require("labkey");

var triggerHelper = new org.labkey.mcc.query.TriggerHelper(LABKEY.Security.currentUser.id, LABKEY.Security.currentContainer.id);

function beforeInsert(row, errors){
    beforeUpsert(row, null, errors);
}

function beforeUpdate(row, oldRow, errors){
    beforeUpsert(row, oldRow, errors);
}

function beforeUpsert(row, oldRow, errors) {
    if (!row.objectid && row.rowid) {
        row.objectid = triggerHelper.resolveObjectId(row.rowid)
    }

    if (!row.objectid) {
        errors._form = 'Unable to resolve record with rowid: ' + row.rowid
        return
    }
    else {
        row.objectid = row.objectid.toUpperCase();
    }

    if (!row.status && oldRow) {
        row.status = oldRow.status;
    }

    if (!row.status) {
        console.error('Request row being submitted without a status: ' + row.objectid)
        console.error(row)
        console.error(oldRow)
    }

    row.status = row.status || 'Draft'

    if (!triggerHelper.hasPermission(row.status)) {
        errors._form = 'Insufficient permissions to update request with status: ' + row.status;

    }
}

function afterInsert(row, errors){
    afterUpsert(row, null, errors);
}

function afterUpdate(row, oldRow, errors){
    afterUpsert(row, oldRow, errors);
}

function afterUpsert(row, oldRow, errors) {
    if (row.status && row.status !== 'Draft') {
        try {
            triggerHelper.ensureReviewRecordsCreated(row.objectId, row.status, oldRow ? oldRow.status : null, calculatePreliminaryScore(row, oldRow));
        }
        catch(e) {
            console.error('Error in animalRequest.afterUpsert')
            console.error(e)
            console.error(row)
            console.error(oldRow)
            errors._form = 'Error saving record'
        }
    }
}

// cascade delete co-i, cohorts:
function beforeDelete(row, errors){
    if (!row.status) {
        errors._form = 'Request lacks a status, cannot delete';
        return;
    }

    if (!triggerHelper.hasPermission(row.status)) {
        errors._form = 'Insufficient permissions to delete this request';
        return;
    }

    if (row.objectid) {
        triggerHelper.cascadeDelete('mcc', 'coinvestigators', 'requestid', row.objectid);
        triggerHelper.cascadeDelete('mcc', 'requestcohorts', 'requestid', row.objectid);

        triggerHelper.cascadeDelete('mcc', 'requestreviews', 'requestid', row.objectid);
        triggerHelper.cascadeDelete('mcc', 'requestscores', 'requestid', row.objectid);
    }
    else {
        console.error('MCC animalRequests row lacks objectid. row ID: ' + row.rowid);
    }
}

function calculatePreliminaryScore(row, oldRow) {
    var fields = ['institutiontype', 'earlystageinvestigator', 'fundingsource', 'existingnhpfacilities', 'existingmarmosetcolony', 'breedinganimals'];
    if (oldRow) {
        fields.forEach(function(fieldName){
            if (row[fieldName] === undefined) {
                row[fieldName] = oldRow[fieldName]
            }
        }, this);
    }

    fields.forEach(function(fieldName){
        if (row[fieldName] === undefined || row[fieldName] === null || row[fieldName] === '') {
            console.error('Missing field ' + fieldName + ' in calculatePreliminaryScore: [' + row[fieldName] + ']')
            console.error(row)
            console.error(oldRow)
        }
    }, this);

    // NOTE: the initial score is two, such that the final range is 0-10
    var score = 2;

    score += row.earlystageinvestigator ? 1 : 0;
    if (row.institutiontype === 'minorityServing' || row.institutiontype === 'university') {
        score += 1;
    }
    else if (row.institutiontype === 'commercial') {
        score -= 1;
    }
    else {
        console.error('Unknown MCC institutiontype: ' + row.institutiontype)
    }

    if (['nih', 'other-federal', 'start-up', 'foundation'].indexOf(row.fundingsource) !== -1) {
        score += 1;
    }
    else if (row.fundingsource === 'private' || row.fundingsource === 'no-funding') {
        // no score change
    }
    else {
        console.error('Unknown MCC fundingsource: ' + row.fundingsource)
    }

    score += row.existingnhpfacilities === 'existing' ? 1 : -1;
    score += row.existingmarmosetcolony === 'existing' ? 1 : 0;

    score += ['Request breeding pair', 'Will pair with existing animals'].indexOf(row.breedinganimals) !== -1 ? 1 : 0;

    return score;
}