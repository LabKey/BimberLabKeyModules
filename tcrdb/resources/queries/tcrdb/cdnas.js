var console = require("console");
var LABKEY = require("labkey");
var importHelper = org.labkey.tcrdb.ImportHelper.create(LABKEY.Security.currentContainer.id, LABKEY.Security.currentUser.id, 'cdnas');

var wellMap = importHelper.getInitialWells();

function beforeInsert(row, errors){
    beforeUpsert(row, null, errors);
}

function beforeUpdate(row, oldRow, errors){
    beforeUpsert(row, oldRow, errors);
}

var rowIdx = -1;

function beforeUpsert(row, oldRow, errors){
    //check for duplicate plate/well
    oldRow = oldRow || {};
    var rowId = row.rowId || oldRow.rowId || rowIdx;
    rowIdx--;
    var wellArr = [(row.plateId || oldRow.plateId), (row.well || oldRow.well)];
    var wellKey = wellArr.join('<>').toUpperCase();
    if (wellMap[wellKey] && wellMap[wellKey] !== rowId){
        errors.well = 'Duplicate entry for plate/well: ' + wellArr.join('/');
    }
    else {
        wellMap[wellKey] = rowId;
    }

    //Note: this will only work if the incoming row has a container property
    //if (row.sortId && !row.container){
    //    row.container = importHelper.getContainerForSort(row.sortId);
    //}
}