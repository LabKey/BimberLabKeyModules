var console = require("console");
var LABKEY = require("labkey");

var triggerHelper = new org.labkey.mgap.query.TriggerHelper(LABKEY.Security.currentUser.id, LABKEY.Security.currentContainer.id);

function beforeInsert(row, errors){
    // NOTE: in limited cases allow the user to specify the alias themselves. the use-case is two different real IDs that each map to the same animal. we would want them pointing to the same alias.
    if (row.externalAlias) {
        if (!triggerHelper.isAliasInUse(row.externalAlias)) {
            errors.externalAlias = 'An external alias can only be provided when this alias is already in use'
        }
    }

    if (row.subjectname) {
        row.externalAlias = row.externalAlias || triggerHelper.getNextAlias();
    }
}