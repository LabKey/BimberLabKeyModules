Ext4.define('mGAP.window.SequenceDownloadWindow', {
    extend: 'Ext.window.Window',

    statics: {
        buttonHandler: function(dataRegionName, releaseId, el){
            var checked = LABKEY.DataRegions[dataRegionName].getChecked();
            if (!checked.length){
                Ext4.Msg.alert('Error', 'No rows selected');
                return;
            }

            LABKEY.Query.selectRows({
                schemaName: 'mGap',
                queryName: 'sequenceDatasets',
                columns: 'sraAccession',
                filterArray: [LABKEY.Filter.create('rowid', checked.join(';'), LABKEY.Filter.Types.IN)],
                scope: this,
                success: function(results){
                    var sraIDs = [];
                    Ext4.Array.forEach(results.rows, function(row){
                        sraIDs.push(row.sraAccession);
                    }, this);

                    Ext4.create('mGAP.window.SequenceDownloadWindow', {
                        sraIDs: sraIDs
                    }).show(el);
                }
            });
        }
    },

    initComponent: function(){
        Ext4.apply(this, {
            title: 'Download Sequence Data',
            width: 600,
            bodyStyle: 'padding: 5px;',
            items: [{
                html: 'This will redirect you to the SRA website to download ' + this.sraIDs.length + ' datasets.  Please follow the instructions on SRA for the most efficient method to download the files.<br><br>' +
                    'mGAP is an NIH funded project.  If you use these data in a publication, we ask that you please include R24OD021324 in the acknowledgements.',
                border: false,
                style: 'padding-bottom: 20px;'
            }],
            buttons: [{
                text: 'Submit',
                handler: this.onSubmit,
                scope: this
            },{
                text: 'Cancel',
                handler: function (btn) {
                    btn.up('window').close();
                }
            }]
        });

        this.callParent(arguments);
    },

    onSubmit: function(){
        this.close();

        var url = 'https://www.ncbi.nlm.nih.gov/Traces/study/?acc=' + this.sraIDs.join(',');
        if (url.length > 4000){
            Ext4.create('Ext.form.Panel', {
                url: 'https://www.ncbi.nlm.nih.gov/Traces/study/',
                standardSubmit: true,
                method: 'POST'
            }).submit({
                params: {
                    acc: this.sraIDs.join(',')
                }
            });
        }
        else {
            window.location = url;
        }
    }
});