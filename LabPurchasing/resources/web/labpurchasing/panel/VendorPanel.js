Ext4.define('LabPurchasing.panel.VendorPanel', {
    extend: 'LDK.form.Panel',
    alias: 'widget.labpurchasing-vendorpanel',

    initComponent: function() {
        Ext4.apply(this, {
            border: false,
            store: {
                schemaName: 'labpurchasing',
                queryName: 'vendors',
                storeId: 'addNewVendorStore',
                autoLoad: true,
                maxRows: 0,
                metadata: {
                    enabled: {
                        defaultValue: true
                    }
                },
            },
            dockedItems: []
        });

        this.callParent(arguments);
    },

    configureForm: function (store) {
        var items = this.callParent(arguments);

        return [{
            layout: 'column',
            border: false,
            defaults: {
                border: false,
                bodyStyle: 'padding: 5px;'
            },
            items: [{
                columnWidth: 0.5,
                items: items.slice(0, 7)
            },{
                columnWidth: 0.5,
                items: items.slice(7)
            }]
        }];
    },

    loadQuery: function(store, records, success) {
        this.callParent(arguments);

        this.up('window').center();
    },

    doSubmit: function(btn){
        btn.setDisabled(true);

        var plugin = this.getPlugin('labkey-databind');
        plugin.updateRecordFromForm();

        if (!this.store.getNewRecords().length && !this.store.getUpdatedRecords().length && !this.store.getRemovedRecords().length){
            Ext4.Msg.alert('No changes', 'There are no changes, nothing to do', function(){
                var win = this.up('window');

                // Reload vendor store:
                var field = this.formPanel.query('field[name="vendorId"]')[0];
                field.store.sync()

                win.close();
            }, this);
            return;
        }

        function onSuccess(store){
            this.mun(this.store, onError);
            btn.setDisabled(false);

            var store = Ext4.StoreManager.get('labpurchasing||vendors||rowId||vendorName||');
            if (store) {
                store.removeAll();
                store.load();
            }

            Ext4.Msg.alert('Success', 'The new vendor was created', function(){
                this.up('window').close();
            }, this);
        }

        function onError(store, msg, error){
            this.mun(this.store, onSuccess);
            btn.setDisabled(false);
        }

        this.mon(this.store, 'write', onSuccess, this, {single: true});
        this.mon(this.store, 'exception', onError, this, {single: true});
        this.store.sync();
    }
});
