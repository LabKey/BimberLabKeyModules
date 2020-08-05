Ext4.define('mGAP.window.DownloadWindow', {
    extend: 'Ext.window.Window',

    statics: {
        buttonHandler: function(releaseId, el){
            Ext4.create('mGAP.window.DownloadWindow', {
                releaseId: releaseId
            }).show(el)
        }
    },

    initComponent: function(){
        Ext4.apply(this, {
            title: 'Download Release',
            width: 600,
            bodyStyle: 'padding: 5px;',
            items: [{
                html: 'This will download a ZIP containing the selected variant release as a VCF file.  If selected, you can also include the genome FASTA, which is required to use some software. Note: because this VCF is quite large, we have included a link below for instructions on downloading from the command line.<br><br>' +
                    'mGAP is an NIH funded project.  If you use these data in a publication, we ask that you please include R24OD021324 in the acknowledgements.',
                border: false,
                style: 'padding-bottom: 20px;'
            },{
                xtype: 'checkbox',
                fieldLabel: 'Include Genome FASTA',
                labelWidth: 185,
                itemId: 'includeGenome',
                checked: false
            },{
                xtype: 'ldk-linkbutton',
                linkCls: 'labkey-text-link',
                text: 'Click here for instructions on command-line download',
                style: 'padding-bottom: 10px;',
                scope: this,
                handler: function(btn){
                    var parentWindow = btn.up('window');

                    Ext4.Msg.wait('Loading...');
                    LABKEY.Query.selectRows({
                        method: 'POST',
                        schemaName: 'mgap',
                        queryName: 'variantCatalogReleases',
                        columns: 'vcfId/dataid/WebDavUrl,vcfId/library_id/fasta_file/WebDavUrl',
                        filterArray: [LABKEY.Filter.create('rowid', this.releaseId, LABKEY.Filter.Types.EQUAL)],
                        scope: this,
                        failure: LDK.Utils.getErrorCallback(),
                        success: function (results) {
                            Ext4.Msg.hide();

                            if (!results || !results.rows) {
                                Ext4.Msg.alert('Error', 'Unable to find matching rows');
                                return;
                            }

                            var url = LABKEY.ActionURL.getBaseURL(true) + results.rows[0]['vcfId/dataid/WebDavUrl'];
                            var urlFasta = LABKEY.ActionURL.getBaseURL(true) + results.rows[0]['vcfId/library_id/fasta_file/WebDavUrl'];

                            parentWindow.close();
                            Ext4.create('Ext.window.Window', {
                                title: 'Command Line VCF Download',
                                bodyStyle: 'padding: 5px;',
                                width: '80%',
                                defaults: {
                                    border: false
                                },
                                items: [{
                                    html: 'Either wget or curl can be used to download the release VCF, similar to the commands below:<br><br>' +
                                            'wget --user=\'' + LABKEY.Security.currentUser.email + '\' --ask-password ' + url + '<br><br>' +
                                            'and also the VCF index:<br><br>' +
                                            'wget --user=\'' + LABKEY.Security.currentUser.email + '\' --ask-password ' + url + '.tbi<br><br>' +
                                            'and genome:<br><br>' +
                                            'wget --user=\'' + LABKEY.Security.currentUser.email + '\' --ask-password ' + urlFasta + '<br>' +
                                            'wget --user=\'' + LABKEY.Security.currentUser.email + '\' --ask-password ' + urlFasta + '.fai<br>' +
                                            'wget --user=\'' + LABKEY.Security.currentUser.email + '\' --ask-password ' + urlFasta.replace(/fasta$/, 'dict')
                                }],
                                buttonAlign: 'left',
                                buttons: [{
                                    text: 'Close',
                                    handler: function(btn) {
                                        btn.up('window').close();
                                    }
                                }]
                            }).show();
                        }
                    });
                }
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
        var includeGenome = this.down('#includeGenome').getValue();

        Ext4.create('Ext.form.Panel', {
            url: LABKEY.ActionURL.buildURL('mgap', 'downloadBundle'),
            standardSubmit: true
        }).submit({
            params: {
                includeGenome: includeGenome,
                releaseId: this.releaseId
            }
        });

        this.close();
    }
});