Ext4.define('TCRdb.window.ExportDataWindow', {
    extend: 'Ext.window.Window',

    statics: {
        viewAlignmentHandler: function(dataRegionName, ownerEl){
            var dataRegion = LABKEY.DataRegions[dataRegionName];
            var actionName = 'exportAlignments';
            var searchKey = 'assayRowIds';

            Ext4.Msg.wait('Loading...');
            dataRegion.getSelected({
                success: function(data, response){
                    Ext4.Msg.hide();
                    var keys = [];
                    Ext4.Array.forEach(data.selected, function(d){
                        d = d.split(',');
                        keys = keys.concat(d);
                    }, this);
                    keys = Ext4.Array.unique(keys);

                    if (!keys.length){
                        Ext4.Msg.alert('Error', 'No Rows Selected');
                        return;
                    }

                    var checked = LABKEY.DataRegions[dataRegionName].getChecked();
                    LDK.Assert.assertEquality('getChecked and getSelected do not match', data.selected.length, checked.length)

                    if (!checked.length){
                        Ext4.Msg.alert('Error', 'No Rows Selected');
                        return;
                    }

                    var win = Ext4.create('TCRdb.window.ExportDataWindow', {
                        dataRegionName: dataRegionName,
                        selected: checked,
                        actionName: actionName,
                        searchKey: searchKey
                    }).show(ownerEl);
                },
                failure: LDK.Utils.getErrorCallback(),
                scope: this
            });
        },

        exportReadsHandler: function(dataRegionName, ownerEl){

        }
    },

    initComponent: function(){
        Ext4.QuickTips.init();
        Ext4.apply(this, {
            width: 500,
            modal: true,
            title: 'Export TCR Data',
            bodyStyle: 'padding: 5px;',
            items: [{
                html: 'The following options can be used to filter the alignments shown.  If left blank, all alignments from the selected samples will be displayed.',
                border: false,
                style: 'padding-bottom: 10px;'
            },{
                xtype: 'textfield',
                itemId: 'cdr3Equals',
                fieldLabel: 'CDR3 Equals (NT)',
                helpPopup: 'Only output reads where the CDR3 equals this NT sequence'
            },{
                xtype: 'textfield',
                itemId: 'readContains',
                fieldLabel: 'Read Contains (NT)',
                helpPopup: 'Only output reads where the read contains this NT sequence'
            }],
            buttons: [{
                text: 'Submit',
                scope: this,
                handler: this.onSubmit
            },{
                text: 'Cancel',
                handler: function(btn){
                    btn.up('window').close();
                }
            }]
        });

        this.callParent(arguments);
    },

    onSubmit: function(){
        var cdr3Equals = this.down('#cdr3Equals').getValue();
        var readContains = this.down('#readContains').getValue();

        if (this.selected.length > 100) {
            var newForm = document.createElement('form');
            newForm.method = 'post';
            newForm.target = '_blank';
            newForm.action = LABKEY.ActionURL.buildURL('tcrdb', this.actionName);
            document.body.appendChild(newForm);

            var csrfElement = document.createElement('input');
            csrfElement.setAttribute('name', 'X-LABKEY-CSRF');
            csrfElement.setAttribute('type', 'hidden');
            csrfElement.setAttribute('value', LABKEY.CSRF);
            newForm.appendChild(csrfElement);

            Ext4.Array.forEach(this.selected, function (s) {
                var newElement = document.createElement('input');
                newElement.setAttribute('name', this.searchKey);
                newElement.setAttribute('type', 'hidden');
                newElement.setAttribute('value', s);
                newForm.appendChild(newElement);
            }, this);

            var dataRegion = LABKEY.DataRegions[this.dataRegionName];
            var newElement = document.createElement('input');
            newElement.setAttribute('name', 'schemaName');
            newElement.setAttribute('type', 'hidden');
            newElement.setAttribute('value', dataRegion.schemaName);
            newForm.appendChild(newElement);

            if (cdr3Equals){
                var newElement = document.createElement('input');
                newElement.setAttribute('name', 'cdr3Equals');
                newElement.setAttribute('type', 'hidden');
                newElement.setAttribute('value', cdr3Equals);
                newForm.appendChild(newElement);
            }

            if (readContains){
                var newElement = document.createElement('input');
                newElement.setAttribute('name', 'readContains');
                newElement.setAttribute('type', 'hidden');
                newElement.setAttribute('value', readContains);
                newForm.appendChild(newElement);
            }

            newForm.submit();
        }
        else {
            var params = {};
            var dataRegion = LABKEY.DataRegions[this.dataRegionName];
            params.schemaName = dataRegion.schemaName;
            params[this.searchKey] = this.selected;
            if (cdr3Equals)
                params.cdr3Equals = cdr3Equals;
            if (readContains)
                params.readContains = readContains;

            window.open(LABKEY.ActionURL.buildURL('tcrdb', this.actionName, null, params));
        }
    }
});