Ext4.define('TCRdb.panel.LibraryExportPanel', {
    extend: 'Ext.panel.Panel',
    alias: 'widget.tcrdb-libraryexportpanel',

    statics: {
        BARCODES5: ['N701', 'N702', 'N703', 'N704', 'N705', 'N706', 'N707', 'N708', 'N709', 'N710', 'N711', 'N712'],

        BARCODES3: ['S517', 'S502', 'S503', 'S504', 'S505', 'S506', 'S507', 'S508'],

        TENX_BARCODES: ['SI-GA-A1','SI-GA-A2','SI-GA-A3','SI-GA-A4','SI-GA-A5','SI-GA-A6','SI-GA-A7','SI-GA-A8','SI-GA-A9','SI-GA-A10','SI-GA-A11','SI-GA-A12','SI-GA-B1','SI-GA-B2','SI-GA-B3','SI-GA-B4','SI-GA-B5','SI-GA-B6','SI-GA-B7','SI-GA-B8','SI-GA-B9','SI-GA-B10','SI-GA-B11','SI-GA-B12','SI-GA-C1','SI-GA-C2','SI-GA-C3','SI-GA-C4','SI-GA-C5','SI-GA-C6','SI-GA-C7','SI-GA-C8','SI-GA-C9','SI-GA-C10','SI-GA-C11','SI-GA-C12','SI-GA-D1','SI-GA-D2','SI-GA-D3','SI-GA-D4','SI-GA-D5','SI-GA-D6','SI-GA-D7','SI-GA-D8','SI-GA-D9','SI-GA-D10','SI-GA-D11','SI-GA-D12','SI-GA-E1','SI-GA-E2','SI-GA-E3','SI-GA-E4','SI-GA-E5','SI-GA-E6','SI-GA-E7','SI-GA-E8','SI-GA-E9','SI-GA-E10','SI-GA-E11','SI-GA-E12','SI-GA-F1','SI-GA-F2','SI-GA-F3','SI-GA-F4','SI-GA-F5','SI-GA-F6','SI-GA-F7','SI-GA-F8','SI-GA-F9','SI-GA-F10','SI-GA-F11','SI-GA-F12','SI-GA-G1','SI-GA-G2','SI-GA-G3','SI-GA-G4','SI-GA-G5','SI-GA-G6','SI-GA-G7','SI-GA-G8','SI-GA-G9','SI-GA-G10','SI-GA-G11','SI-GA-G12','SI-GA-H1','SI-GA-H2','SI-GA-H3','SI-GA-H4','SI-GA-H5','SI-GA-H6','SI-GA-H7','SI-GA-H8','SI-GA-H9','SI-GA-H10','SI-GA-H11','SI-GA-H12']
    },

    initComponent: function () {
        Ext4.apply(this, {
            title: null,
            border: false,
            defaults: {
                border: false
            },
            items: [{
                xtype: 'radiogroup',
                name: 'importType',
                columns: 1,
                items: [{
                    boxLabel: 'Novogene/Plate List',
                    inputValue: 'plateList',
                    name: 'importType',
                    checked: true
                },{
                    boxLabel: 'Other',
                    inputValue: 'other',
                    name: 'importType'
                }],
                listeners: {
                    scope: this,
                    afterrender: function(field) {
                        field.fireEvent('change', field, field.getValue());
                    },
                    change: function(field, val) {
                        val = val.importType;
                        var target = field.up('panel').down('#importArea');
                        target.removeAll();
                        if (val === 'other') {
                            target.add([{
                                xtype: 'ldk-simplecombo',
                                itemId: 'instrument',
                                fieldLabel: 'Instrument/Core',
                                forceSelection: true,
                                editable: false,
                                labelWidth: 160,
                                storeValues: ['NextSeq (MPSSR)', 'MiSeq (ONPRC)', 'Basic List (MedGenome)', '10x Sample Sheet', 'Novogene']
                            },{
                                xtype: 'ldk-simplecombo',
                                itemId: 'application',
                                fieldLabel: 'Application/Type',
                                forceSelection: true,
                                editable: true,
                                labelWidth: 160,
                                allowBlank: true,
                                storeValues: ['Whole Transcriptome RNA-Seq', 'TCR Enriched', '10x GEX', '10x VDJ']
                            },{
                                xtype: 'labkey-combo',
                                forceSelection: true,
                                multiSelect: true,
                                displayField: 'plateId',
                                valueField: 'plateId',
                                itemId: 'sourcePlates',
                                fieldLabel: 'Source Plate Id',
                                store: {
                                    type: 'labkey-store',
                                    schemaName: 'tcrdb',
                                    sql: 'SELECT distinct plateId as plateId from tcrdb.cdnas c WHERE c.allReadsetsHaveData = false',
                                    autoLoad: true
                                },
                                labelWidth: 160
                            },{
                                xtype: 'textfield',
                                itemId: 'adapter',
                                fieldLabel: 'Adapter',
                                labelWidth: 160,
                                value: 'CTGTCTCTTATACACATCT'
                            }]);
                        }
                        else {
                            target.add({
                                border: false,
                                defaults: {
                                    border: false
                                },
                                items: [{
                                    html: 'Add an ordered list of plates, using tab-delimited columns.  The first column(s) are plate ID and library type (GEX, VDJ, or HTO).  These can either be one column (i.e. G234-1 or T234-1), or as two columns (234-1 GEX or 234-1    HTO). An optional next column is the lane assignment (i.e. Novaseq1, HiSeq1, HiSeq2). Finally, an optional final column can be used to provide the alias for this pool. This is mostly used for HTOs, where multiple libraries are pre-pooled (such as HTOs).  See these examples:<br>' +
                                            '<pre>' +
                                                '234-2\tGEX<br>' +
                                                '234-2\tVDJ<br>' +
                                                'G233-2<br>' +
                                                'T235-2<br>' +
                                                '234-2\tVDJ\tNovaSeq1<br>' +
                                                'G233-2\tNovaSeq1<br>' +
                                                '235-2\tHTO\tHiSeq1\tBNB-HTO-1<br>' +
                                                'H235-2\tHiSeq1\tBNB-HTO-1<br>' +
                                                '235-2\tHTO\tHiSeq2\tBNB-HTO-1<br>' +
                                                'H235-2\tHiSeq1\tBNB-HTO-1' +
                                            '</pre>',
                                    border: false
                                },{
                                    xtype: 'hidden',
                                    itemId: 'instrument',
                                    value: 'Novogene'
                                },{
                                    xtype: 'textarea',
                                    itemId: 'plateList',
                                    fieldLabel: 'Plate List',
                                    labelAlign: 'top',
                                    width: 270,
                                    height: 200,
                                    enableKeyEvents: true,
                                    listeners: {
                                        specialkey: function (field, e) {
                                            if (e.getKey() === e.TAB) {
                                                field.setValue(field.getValue() + '\t');
                                                e.preventDefault();
                                            }
                                        }
                                    },
                                },{
                                    xtype: 'ldk-numberfield',
                                    itemId: 'defaultVolume',
                                    fieldLabel: 'Default Volume (uL)',
                                    value: 10
                                }],
                                buttonAlign: 'left',
                                buttons: [{
                                    text: 'Add',
                                    scope: this,
                                    handler: function (btn) {
                                        var text = btn.up('panel').down('#plateList').getValue();
                                        if (!text) {
                                            Ext4.Msg.alert('Error', 'Must enter a list of plates');
                                            return;
                                        }

                                        text = LDK.Utils.CSVToArray(Ext4.String.trim(text), '\t');
                                        Ext4.Array.forEach(text, function(r, idx){
                                            var val = r[0];
                                            if (val.startsWith('G')){
                                                val = val.substr(1);
                                                val = val.replace('_', '-');
                                                r[0] = 'GEX';
                                                r.unshift(val);

                                            }
                                            else if (val.startsWith('T')){
                                                val = val.substr(1);
                                                val = val.replace('_', '-');
                                                r[0] = 'VDJ';
                                                r.unshift(val);
                                            }
                                            else if (val.startsWith('H')){
                                                val = val.substr(1);
                                                val = val.replace('_', '-');
                                                r[0] = 'HTO';
                                                r.unshift(val);
                                            }
                                        }, this);

                                        var hadError = false;
                                        Ext4.Array.forEach(text, function(r){
                                            if (r.length < 2){
                                                hadError = true;
                                            }

                                            //ensure all rows are of length 4
                                            if (r.length !== 4) {
                                                for (i=0;i<(4-r.length);i++) {
                                                    r.push('');
                                                }
                                            }
                                        }, this);

                                        if (hadError) {
                                            Ext4.Msg.alert('Error', 'All rows must have at least 2 values');
                                            return;
                                        }

                                        this.onSubmit(btn, text);
                                    }
                                }]
                            });
                        }
                    }
                }
            }, {
                bodyStyle: 'padding: 5px;',
                itemId: 'importArea',
                border: false,
                defaults: {
                    border: false
                }
            },{
                xtype: 'checkbox',
                boxLabel: 'Allow Duplicate Barcodes',
                checked: false,
                itemId: 'allowDuplicates'
            },{
                xtype: 'checkbox',
                boxLabel: 'Use Simple Sample Names',
                checked: true,
                itemId: 'simpleSampleNames'
            },{
                xtype: 'checkbox',
                boxLabel: 'Include Blanks',
                checked: true,
                itemId: 'includeBlanks'
            },{
                xtype: 'checkbox',
                boxLabel: 'Include Libraries With Data',
                checked: false,
                itemId: 'includeWithData',
                listeners: {
                    change: function (field, val) {
                        var target = field.up('tcrdb-libraryexportpanel').down('#sourcePlates');
                        var sql = 'SELECT distinct plateId as plateId from tcrdb.cdnas ' + (val ? '' : 'c WHERE c.allReadsetsHaveData = false');
                        target.store.sql = sql;
                        target.store.removeAll();
                        target.store.load(function () {
                            if (target.getPicker()) {
                                target.getPicker().refresh();
                            }
                        }, this);
                    }
                }
            },{
                xtype: 'textarea',
                itemId: 'outputArea',
                fieldLabel: 'Output',
                labelAlign: 'top',
                width: 1000,
                height: 400
            }],
            buttonAlign: 'left',
            buttons: [{
                text: 'Submit',
                scope: this,
                handler: function(btn){
                    this.onSubmit(btn);
                }
            },{
                text: 'Download Data',
                itemId: 'downloadData',
                disabled: true,
                handler: function(btn){
                    var instrument = btn.up('tcrdb-libraryexportpanel').down('#instrument').getValue();
                    var plateId = btn.up('tcrdb-libraryexportpanel').down('#sourcePlates').getValue();
                    var delim = 'TAB';
                    var extention = 'txt';
                    var split = '\t';
                    if (instrument !== 'NextSeq (MPSSR)'){
                        delim = 'COMMA';
                        extention = 'csv';
                        split = ',';
                    }

                    var val = btn.up('tcrdb-libraryexportpanel').down('#outputArea').getValue();
                    var rows = LDK.Utils.CSVToArray(Ext4.String.trim(val), split);

                    LABKEY.Utils.convertToTable({
                        fileName: plateId + '.' + extention,
                        rows: rows,
                        delim: delim
                    });
                }
            }]
        });

        this.callParent(arguments);

        Ext4.Msg.wait('Loading...');
        LABKEY.Query.selectRows({
            schemaName: 'sequenceanalysis',
            queryName: 'barcodes',
            sort: 'group_name,tag_name',
            scope: this,
            failure: LDK.Utils.getErrorCallback(),
            success: function(results){
                this.barcodeMap = {};

                Ext4.Array.forEach(results.rows, function(r){
                    this.barcodeMap[r.group_name] = this.barcodeMap[r.group_name] || {};
                    this.barcodeMap[r.group_name][r.tag_name] = r.sequence;
                }, this);

                Ext4.Msg.hide();
            }
        });
    },

    onSubmit: function(btn, expectedPairs){
        var plateIds = [];

        if (expectedPairs) {
            var hadError = false;
            Ext4.Array.forEach(expectedPairs, function(p){
                plateIds.push(p[0]);
            }, this);
        }
        else {
            plateIds = btn.up('tcrdb-libraryexportpanel').down('#sourcePlates').getValue();
        }

        if (!plateIds || !plateIds.length){
            Ext4.Msg.alert('Error', 'Must provide the plate Id(s)');
            return;
        }

        var instrument = btn.up('tcrdb-libraryexportpanel').down('#instrument').getValue();
        var application = btn.up('tcrdb-libraryexportpanel').down('#application') ? btn.up('tcrdb-libraryexportpanel').down('#application').getValue() :  null;
        var defaultVolume = btn.up('tcrdb-libraryexportpanel').down('#defaultVolume') ? btn.up('tcrdb-libraryexportpanel').down('#defaultVolume').getValue() :  '';
        var adapter = btn.up('tcrdb-libraryexportpanel').down('#adapter') ? btn.up('tcrdb-libraryexportpanel').down('#adapter').getValue() : null;
        var includeWithData = btn.up('tcrdb-libraryexportpanel').down('#includeWithData').getValue();
        var allowDuplicates = btn.up('tcrdb-libraryexportpanel').down('#allowDuplicates').getValue();
        var simpleSampleNames = btn.up('tcrdb-libraryexportpanel').down('#simpleSampleNames').getValue();
        var includeBlanks = btn.up('tcrdb-libraryexportpanel').down('#includeBlanks').getValue();
        var doReverseComplement = btn.up('tcrdb-libraryexportpanel').doReverseComplement;

        var isMatchingApplication = function(application, libraryType, readsetApplication, rowLevelApplication){
            if (!application && !rowLevelApplication){
                return true;
            }

            if (application === 'Whole Transcriptome RNA-Seq'){
                return readsetApplication === 'RNA-seq' || readsetApplication === 'RNA-seq, Single Cell';
            }
            else if (application === 'TCR Enriched'){
                return readsetApplication === 'RNA-seq + Enrichment';
            }
            else if (readsetApplication === 'RNA-seq, Single Cell'){
                application = rowLevelApplication || application;
                return (libraryType === '10x 5\' GEX' && application === '10x GEX') || (libraryType === '10x 5\' VDJ (Rhesus A/B/G)' && application === '10x VDJ');
            }
            else if (readsetApplication === 'Cell Hashing'){
                application = rowLevelApplication || application;
                return (application === '10x HTO');
            }
        };

        var getSampleName = function(simpleSampleNames, readsetId, readsetName, suffix){
            return (simpleSampleNames ? 's_' + readsetId : readsetId + '_' + readsetName) + (suffix ? '_' + suffix : '');
        };

        Ext4.Msg.wait('Loading cDNA data');
        LABKEY.Query.selectRows({
            containerPath: Laboratory.Utils.getQueryContainerPath(),
            schemaName: 'tcrdb',
            queryName: 'cdnas',
            sort: 'plateId,well/addressByColumn',
            columns: 'rowid,plateid' +
                ',readsetId,readsetId/name,readsetId/application,readsetId/librarytype,readsetId/barcode5,readsetId/barcode5/sequence,readsetId/barcode3,readsetId/barcode3/sequence,readsetId/totalFiles,readsetId/concentration' +
                ',enrichedReadsetId,enrichedReadsetId/name,enrichedReadsetId/application,enrichedReadsetId/librarytype,enrichedReadsetId/barcode5,enrichedReadsetId/barcode5/sequence,enrichedReadsetId/barcode3,enrichedReadsetId/barcode3/sequence,enrichedReadsetId/totalFiles,enrichedReadsetId/concentration' +
            ',hashingReadsetId,hashingReadsetId/name,hashingReadsetId/application,hashingReadsetId/librarytype,hashingReadsetId/barcode5,hashingReadsetId/barcode5/sequence,hashingReadsetId/barcode3,hashingReadsetId/barcode3/sequence,hashingReadsetId/totalFiles,hashingReadsetId/concentration',
            scope: this,
            filterArray: [LABKEY.Filter.create('plateId', plateIds.join(';'), LABKEY.Filter.Types.IN)],
            failure: LDK.Utils.getErrorCallback(),
            success: function (results) {
                Ext4.Msg.hide();

                if (!results || !results.rows || !results.rows.length) {
                    Ext4.Msg.alert('Error', 'No libraries found for the selected plates');
                    return;
                }

                var sortedRows = results.rows;
                if (expectedPairs) {
                    sortedRows = [];
                    var missingRows = [];
                    Ext4.Array.forEach(expectedPairs, function(p){
                        var found = false;
                        Ext4.Array.forEach(results.rows, function(row){
                            if (row.plateId === p[0]) {
                                if (p[1] === 'GEX') {
                                    if (includeWithData || row['readsetId/totalFiles'] === 0) {
                                        if (row['readsetId/librarytype'].match('GEX')) {
                                            sortedRows.push(Ext4.apply({targetApplication: '10x GEX', laneAssignment: (p.length > 2 ? p[2] : null), plateAlias: (p.length > 3 ? p[3] : null)}, row));
                                            found = true;
                                            return false;
                                        }
                                    }
                                }
                                else if (p[1] === 'HTO') {
                                    if (includeWithData || row['hashingReadsetId/totalFiles'] === 0) {
                                        if (row['hashingReadsetId/application'].match('Cell Hashing')) {
                                            sortedRows.push(Ext4.apply({targetApplication: '10x HTO', laneAssignment: (p.length > 2 ? p[2] : null), plateAlias: (p.length > 3 ? p[3] : null)}, row));
                                            found = true;
                                            return false;
                                        }
                                    }
                                }
                                else if (p[1] === 'VDJ') {
                                    if (includeWithData || row['enrichedReadsetId/totalFiles'] === 0) {
                                        if (row['enrichedReadsetId/librarytype'].match('VDJ')) {
                                            sortedRows.push(Ext4.apply({targetApplication: '10x VDJ', laneAssignment: (p.length > 2 ? p[2] : null), plateAlias: (p.length > 3 ? p[3] : null)}, row));
                                            found = true;
                                            return false;
                                        }
                                    }
                                }
                            }
                        }, this);

                        if (!found) {
                            missingRows.push(p[0] + '/' + p[1]);
                        }
                    }, this);

                    if (missingRows.length){
                        Ext4.Msg.alert('Error', 'The following plates were not found:<br>' + missingRows.join('<br>'));
                        return;
                    }
                }

                var barcodes = 'Illumina';
                var readsetIds = {};
                var barcodeCombosUsed = [];
                if (instrument === 'NextSeq (MPSSR)' || instrument === 'Basic List (MedGenome)') {
                    var rc5 = (instrument === 'NextSeq (MPSSR)');
                    var rc3 = (instrument === 'NextSeq (MPSSR)');

                    var rows = [['Name', 'Adapter', 'I7_Index_ID', 'I7_Seq', 'I5_Index_ID', 'I5_Seq'].join('\t')];
                    Ext4.Array.forEach(sortedRows, function (r) {
                        //only include readsets without existing data
                        if (!readsetIds[r.readsetId] && r.readsetId && (includeWithData || r['readsetId/totalFiles'] === 0) && isMatchingApplication(application, r['readsetId/librarytype'], r['readsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.readsetId] = true;

                            //reverse complement both barcodes:
                            var barcode5 = rc5 ? doReverseComplement(r['readsetId/barcode5/sequence']) : r['readsetId/barcode5/sequence'];
                            var barcode3 = rc3 ? doReverseComplement(r['readsetId/barcode3/sequence']) : r['readsetId/barcode3/sequence'];
                            barcodeCombosUsed.push(r['readsetId/barcode5'] + '/' + r['readsetId/barcode3']);
                            rows.push([getSampleName(simpleSampleNames, r.readsetId, r['readsetId/name']), adapter, r['readsetId/barcode5'], barcode5, r['readsetId/barcode3'], barcode3].join('\t'));
                        }

                        if (!readsetIds[r.enrichedReadsetId] && r.enrichedReadsetId && (includeWithData || r['enrichedReadsetId/totalFiles'] === 0) && isMatchingApplication(application, r['enrichedReadsetId/librarytype'], r['enrichedReadsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.enrichedReadsetId] = true;

                            var barcode5 = rc5 ? doReverseComplement(r['enrichedReadsetId/barcode5/sequence']) : r['enrichedReadsetId/barcode5/sequence'];
                            var barcode3 = rc3 ? doReverseComplement(r['enrichedReadsetId/barcode3/sequence']) : r['enrichedReadsetId/barcode3/sequence'];
                            barcodeCombosUsed.push(r['enrichedReadsetId/barcode5'] + '/' + r['enrichedReadsetId/barcode3']);
                            rows.push([getSampleName(simpleSampleNames, r.enrichedReadsetId, r['enrichedReadsetId/name']), adapter, r['enrichedReadsetId/barcode5'], barcode5, r['enrichedReadsetId/barcode3'], barcode3].join('\t'))
                        }

                        if (!readsetIds[r.hashingReadsetId] && r.hashingReadsetId && (includeWithData || r['hashingReadsetId/totalFiles'] === 0) && isMatchingApplication(application, r['hashingReadsetId/librarytype'], r['hashingReadsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.hashingReadsetId] = true;

                            var barcode5 = rc5 ? doReverseComplement(r['hashingReadsetId/barcode5/sequence']) : r['hashingReadsetId/barcode5/sequence'];
                            var barcode3 = rc3 ? doReverseComplement(r['hashingReadsetId/barcode3/sequence']) : r['hashingReadsetId/barcode3/sequence'];
                            barcodeCombosUsed.push(r['hashingReadsetId/barcode5'] + '/' + r['hashingReadsetId/barcode3']);
                            rows.push([getSampleName(simpleSampleNames, r.hashingReadsetId, r['hashingReadsetId/name']), adapter, r['hashingReadsetId/barcode5'], barcode5, r['hashingReadsetId/barcode3'], barcode3].join('\t'))
                        }
                    }, this);

                    //add missing barcodes:
                    if (includeBlanks) {
                        var blankIdx = 0;
                        Ext4.Array.forEach(TCRdb.panel.LibraryExportPanel.BARCODES5, function (barcode5) {
                            Ext4.Array.forEach(TCRdb.panel.LibraryExportPanel.BARCODES3, function (barcode3) {
                                var combo = barcode5 + '/' + barcode3;
                                if (barcodeCombosUsed.indexOf(combo) === -1) {
                                    blankIdx++;
                                    var barcode5Seq = rc5 ? doReverseComplement(this.barcodeMap[barcodes][barcode5]) : this.barcodeMap[barcodes][barcode5];
                                    var barcode3Seq = rc3 ? doReverseComplement(this.barcodeMap[barcodes][barcode3]) : this.barcodeMap[barcodes][barcode3];

                                    var name = simpleSampleNames ? 's_Blank' + blankIdx : plateIds.join(';').replace(/\//g, '-') + '_Blank' + blankIdx;
                                    rows.push([name, adapter, barcode5, barcode5Seq, barcode3, barcode3Seq].join('\t'));
                                }
                            }, this);
                        }, this);
                    }
                }
                else if (instrument === 'MiSeq (ONPRC)') {
                    var rows = [];
                    rows.push('[Header]');
                    rows.push('IEMFileVersion,4');
                    rows.push('Investigator Name,Bimber');
                    rows.push('Experiment Name,' + plateIds.join(';'));
                    rows.push('Date,11/16/2017');
                    rows.push('Workflow,GenerateFASTQ');
                    rows.push('Application,FASTQ Only');
                    rows.push('Assay,Nextera XT');
                    rows.push('Description,');
                    rows.push('Chemistry,Amplicon');
                    rows.push('');
                    rows.push('[Reads]');
                    rows.push('251');
                    rows.push('251');
                    rows.push('');
                    rows.push('[Settings]');
                    rows.push('ReverseComplement,0');
                    rows.push('Adapter,' + adapter);
                    rows.push('');
                    rows.push('[Data]');
                    rows.push('Sample_ID,Sample_Name,Sample_Plate,Sample_Well,I7_Index_ID,index,I5_Index_ID,index2,Sample_Project,Description');

                    Ext4.Array.forEach(sortedRows, function (r) {
                        //only include readsets without existing data
                        if (!readsetIds[r.readsetId] && r.readsetId && (includeWithData || r['readsetId/totalFiles'] === 0) && isMatchingApplication(application, r['readsetId/librarytype'], r['readsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.readsetId] = true;

                            //reverse complement both barcodes:
                            var barcode5 = doReverseComplement(r['readsetId/barcode5/sequence']);
                            var barcode3 = r['readsetId/barcode3/sequence'];
                            var cleanedName = r.readsetId + '_' + r['readsetId/name'].replace(/ /g, '_');
                            cleanedName = cleanedName.replace(/\//g, '-');

                            barcodeCombosUsed.push(r['readsetId/barcode5'] + '/' + r['readsetId/barcode3']);
                            rows.push([r.readsetId, cleanedName, '', '', r['readsetId/barcode5'], barcode5, r['readsetId/barcode3'], barcode3].join(','));
                        }

                        if (!readsetIds[r.enrichedReadsetId] && r.enrichedReadsetId && (includeWithData || r['enrichedReadsetId/totalFiles'] == 0) && isMatchingApplication(application, r['enrichedReadsetId/librarytype'], r['enrichedReadsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.enrichedReadsetId] = true;

                            var barcode5 = doReverseComplement(r['enrichedReadsetId/barcode5/sequence']);
                            var barcode3 = r['enrichedReadsetId/barcode3/sequence'];
                            var cleanedName = r.enrichedReadsetId + '_' + r['enrichedReadsetId/name'].replace(/ /g, '_');
                            cleanedName = cleanedName.replace(/\//g, '-');

                            barcodeCombosUsed.push(r['enrichedReadsetId/barcode5'] + '/' + r['enrichedReadsetId/barcode3']);
                            rows.push([r.enrichedReadsetId, cleanedName, '', '', r['enrichedReadsetId/barcode5'], barcode5, r['enrichedReadsetId/barcode3'], barcode3].join(','))
                        }
                    }, this);

                    //add missing barcodes:
                    if (includeBlanks) {
                        var blankIdx = 0;
                        Ext4.Array.forEach(TCRdb.panel.LibraryExportPanel.BARCODES5, function (barcode5) {
                            Ext4.Array.forEach(TCRdb.panel.LibraryExportPanel.BARCODES3, function (barcode3) {
                                var combo = barcode5 + '/' + barcode3;
                                if (barcodeCombosUsed.indexOf(combo) === -1) {
                                    blankIdx++;
                                    var barcode5Seq = doReverseComplement(this.barcodeMap[barcodes][barcode5]);
                                    var barcode3Seq = this.barcodeMap[barcodes][barcode3];
                                    rows.push([plateIds.join(';').replace(/\//g, '-') + '_Blank' + blankIdx, null, null, null, barcode5, barcode5Seq, barcode3, barcode3Seq].join(','));
                                }
                            }, this);
                        }, this);
                    }
                }
                else if (instrument === '10x Sample Sheet' || instrument === 'Novogene') {
                    var doRC = false;
                    var rows = [];
                    var barcodes = '10x Chromium Single Cell v2';

                    if (instrument === '10x Sample Sheet') {
                        rows.push('Sample_ID,Sample_Name,index,Sample_Project');
                    }

                    var delim = instrument === 'Novogene' ? '\t' : ',';
                    Ext4.Array.forEach(sortedRows, function (r) {
                        //only include readsets without existing data
                        if (!readsetIds[r.readsetId] && r.readsetId && (includeWithData || r['readsetId/totalFiles'] === 0) && isMatchingApplication(application, r['readsetId/librarytype'], r['readsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.readsetId] = true;

                            var barcode5s = r['readsetId/barcode5/sequence'].split(',');
                            barcodeCombosUsed.push(r['readsetId/barcode5']);
                            Ext4.Array.forEach(barcode5s, function(bc, idx){
                                var cleanedName = r.readsetId + '_' + r['readsetId/name'].replace(/ /g, '_');
                                cleanedName = cleanedName.replace(/\//g, '-');
                                bc = doRC ? doReverseComplement(bc) : bc;

                                var sampleName = getSampleName(simpleSampleNames, r.readsetId, r['readsetId/name']);
                                var data = [sampleName, (instrument === 'Novogene' ? '' : cleanedName), bc, ''];
                                if (instrument === 'Novogene') {
                                    data = [sampleName];
                                    if (r.plateAlias) {
                                        data.unshift(r.plateAlias);
                                    }
                                    else {
                                        data.unshift('G' + r.plateId.replace(/-/g, '_'));
                                    }

                                    data.push('Macaca mulatta');
                                    data.push(bc);
                                    data.push('');
                                    data.push(r['readsetId/concentration'] || '');
                                    data.push(defaultVolume);
                                    data.push('');
                                    data.push('500');
                                    data.push('1');  //PhiX
                                    data.push(r.laneAssignment || '');
                                    data.push('Please QC individually and pool in equal amounts per lane');
                                }
                                rows.push(data.join(delim));
                            }, this);
                        }

                        if (!readsetIds[r.enrichedReadsetId] && r.enrichedReadsetId && (includeWithData || r['enrichedReadsetId/totalFiles'] === 0) && isMatchingApplication(application, r['enrichedReadsetId/librarytype'], r['enrichedReadsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.enrichedReadsetId] = true;

                            var barcode5s = r['enrichedReadsetId/barcode5/sequence'].split(',');
                            barcodeCombosUsed.push(r['enrichedReadsetId/barcode5']);
                            Ext4.Array.forEach(barcode5s, function(bc, idx){
                                var cleanedName = r.enrichedReadsetId + '_' + r['enrichedReadsetId/name'].replace(/ /g, '_');
                                cleanedName = cleanedName.replace(/\//g, '-');
                                bc = doRC ? doReverseComplement(bc) : bc;

                                var sampleName = getSampleName(simpleSampleNames, r.enrichedReadsetId, r['enrichedReadsetId/name'], (instrument === 'Novogene' ? '' : '-TCR'));
                                var data = [sampleName, (instrument === 'Novogene' ? '' : cleanedName), bc, ''];
                                if (instrument === 'Novogene') {
                                    data = [sampleName];
                                    if (r.plateAlias) {
                                        data.unshift(r.plateAlias);
                                    }
                                    else {
                                        data.unshift('T' + r.plateId.replace(/-/g, '_'));
                                    }

                                    data.push('Macaca mulatta');
                                    data.push(bc);
                                    data.push('');
                                    data.push(r['enrichedReadsetId/concentration']);
                                    data.push(defaultVolume);
                                    data.push('');
                                    data.push('700');
                                    data.push('1');  //PhiX
                                    data.push(r.laneAssignment || '');
                                    data.push('Please QC individually and pool in equal amounts per lane');
                                }

                                rows.push(data.join(delim));
                            }, this);
                        }

                        if (!readsetIds[r.hashingReadsetId] && r.hashingReadsetId && (includeWithData || r['hashingReadsetId/totalFiles'] === 0) && isMatchingApplication(application, r['hashingReadsetId/librarytype'], r['hashingReadsetId/application'], r.targetApplication)) {
                            //allow for cell hashing / shared readsets
                            readsetIds[r.hashingReadsetId] = true;

                            var barcode5s = r['hashingReadsetId/barcode5/sequence'].split(',');
                            barcodeCombosUsed.push(r['hashingReadsetId/barcode5']);
                            Ext4.Array.forEach(barcode5s, function(bc, idx){
                                var cleanedName = r.hashingReadsetId + '_' + r['hashingReadsetId/name'].replace(/ /g, '_');
                                cleanedName = cleanedName.replace(/\//g, '-');

                                //NOTE: the hashing barcodes need to be reversed relative to database
                                bc = doReverseComplement(bc);

                                var sampleName = getSampleName(simpleSampleNames, r.hashingReadsetId, r['hashingReadsetId/name'], (instrument === 'Novogene' ? '' : '-HTO'));
                                var data = [sampleName, (instrument === 'Novogene' ? '' : cleanedName), bc, ''];
                                if (instrument === 'Novogene') {
                                    data = [sampleName];
                                    if (r.plateAlias) {
                                        data.unshift(r.plateAlias);
                                    }
                                    else {
                                        data.unshift('H' + r.plateId.replace(/-/g, '_'));
                                    }

                                    data.push('Macaca mulatta');
                                    data.push(bc);
                                    data.push('');
                                    data.push(r['hashingReadsetId/concentration']);
                                    data.push(defaultVolume);
                                    data.push('');
                                    data.push('182');
                                    data.push('5');  //PhiX
                                    data.push(r.laneAssignment || '');
                                    data.push('Cell hashing, 190bp amplicon.  Please QC individually and pool in equal amounts per lane');
                                }

                                rows.push(data.join(delim));
                            }, this);
                        }
                    }, this);

                    //add missing barcodes:
                    if (includeBlanks && instrument !== 'Novogene') {
                        var blankIdx = 0;
                        Ext4.Array.forEach(TCRdb.panel.LibraryExportPanel.TENX_BARCODES, function (barcode5) {
                            if (barcodeCombosUsed.indexOf(barcode5) === -1) {
                                blankIdx++;
                                var barcode5Seq = this.barcodeMap[barcodes][barcode5].split(',');
                                Ext4.Array.forEach(barcode5Seq, function (seq, idx) {
                                    seq = doRC ? doReverseComplement(seq) : seq;
                                    rows.push([barcode5 + '_' + (idx + 1), plateIds.join(';').replace(/\//g, '-') + '_Blank' + blankIdx, seq, ''].join(delim));
                                }, this);
                            }
                        }, this);
                    }
                }

                //check for unique barcodes
                var sorted = barcodeCombosUsed.slice().sort();
                var duplicates = [];
                for (var i = 0; i < sorted.length - 1; i++) {
                    if (sorted[i + 1] === sorted[i]) {
                        duplicates.push(sorted[i]);
                    }
                }

                duplicates = Ext4.unique(duplicates);
                if (!allowDuplicates && duplicates.length){
                    Ext4.Msg.alert('Error', 'Duplicate barcodes: ' + duplicates.join(', '));
                    btn.up('tcrdb-libraryexportpanel').down('#outputArea').setValue(null);
                    btn.up('tcrdb-libraryexportpanel').down('#downloadData').setDisabled(true);
                }
                else {
                    btn.up('tcrdb-libraryexportpanel').down('#outputArea').setValue(rows.join('\n'));
                    btn.up('tcrdb-libraryexportpanel').down('#downloadData').setDisabled(false);
                }
            }
        });
    },

    doReverseComplement: function(seq){
        if (!seq){
            return seq;
        }
        var match={'a': 'T', 'A': 'T', 't': 'A', 'T': 'A', 'g': 'C', 'G': 'C', 'c': 'G', 'C': 'G'};
        var o = '';
        for (var i = seq.length - 1; i >= 0; i--) {
            if (match[seq[i]] === undefined) break;
            o += match[seq[i]];
        }

        return o;
    }
});
