window.mGAP = window.mGAP || {};

mGAP.Utils = (function($){


    return {
        renderReleaseGraph: function(outerDiv, width){
            var targetId1 = 'mgap-release-graph1', targetId2 = 'mgap-release-graph2', tableId = 'mgap-release-table', targetId3 = 'mgap-release-graph3';

            if (!mGAP.Utils.getMGapReleaseId()){
                $(targetId1).html('<span>No release information</span>');
                return;
            }

            LABKEY.Query.selectRows({
                schemaName: 'mgap',
                queryName: 'releaseStats',
                filterArray: [LABKEY.Filter.create('releaseId/rowId', mGAP.Utils.getMGapReleaseId(), LABKEY.Filter.Types.EQUAL)],
                failure: LDK.Utils.getErrorCallback(),
                scope: this,
                success: function(results){
                    var map = {};
                    $.each(results.rows, function(idx, val){
                        if (!map[val.category]){
                            map[val.category] = {};
                        }

                        map[val.category][val.metricName] = val.value;
                    });

                    function numberWithCommas(number) {
                        if (!number){
                            return '';
                        }

                        var parts = number.toString().split(".");
                        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
                        return parts.join(".");
                    }

                    var versionLine = '';
                    if (mGAP.Utils.getMGapReleaseVersion()) {
                        versionLine = '<tr><td>Current Version:</td><td>' + mGAP.Utils.getMGapReleaseVersion() + '</td></tr>';
                    }
                    var data0 = map.Counts || {};
                    $('#' + tableId).html('<table>' + versionLine + '<tr><td>Total Variants:</td><td>' + numberWithCommas(data0.TotalVariants) + '</td></tr><tr><td>Total Animals:</td><td>' + numberWithCommas(data0.TotalSamples) + '</td></tr><tr><td style="padding-right: 20px;">Private Variants:</td><td>' + numberWithCommas(data0.TotalPrivateVariants) + '</td></tr></table>');

                    var data1 = map.CodingPotential || {};
                    var codingLabels = ["Exonic", "Downstream<br>Gene", "Upstream<br>Gene", "Intergenic", "Intronic/<br>Non-coding"];
                    var codingData = [];
                    var codingDataMap = {};
                    for (var values in data1){
                        var metricNames = values.split(';');
                        var targets = [];

                        //filter:
                        if (metricNames.length > 1 && metricNames.indexOf('intron_variant') > -1) {
                            metricNames.remove('downstream_gene_variant');
                            metricNames.remove('upstream_gene_variant');
                        }

                        if (metricNames.indexOf('downstream_gene_variant') > -1) {
                            targets.push('Downstream<br>Gene');
                        }
                        if (metricNames.indexOf('upstream_gene_variant') > -1) {
                            targets.push('Upstream<br>Gene');
                        }

                        $.each(metricNames, function(idx, val) {
                            if (['missense_variant', 'synonymous_variant', 'stop_lost', 'stop_retained_variant', 'stop_gained', 'initiator_codon_variant', 'start_lost', 'non_canonical_start_codon', 'exon_loss_variant', 'frameshift_variant', 'conservative_inframe_insertion', 'disruptive_inframe_insertion', 'conservative_inframe_deletion', 'disruptive_inframe_deletion'].indexOf(val) > -1) {
                                targets.push('Exonic');
                                return false;
                            }
                            else if (['downstream_gene_variant'].indexOf(val) > -1) {

                            }
                            else if (['upstream_gene_variant'].indexOf(val) > -1) {

                            }
                            else if (['intron_variant', 'splice_acceptor_variant', 'splice_region_variant', 'splice_donor_variant'].indexOf(val) > -1) {
                                targets.push('Intronic/<br>Non-coding');
                                return false;
                            }
                            else if (['intragenic_variant', 'non_coding_transcript_variant', 'non_coding_transcript_exon_variant', '3_prime_UTR_variant', '5_prime_UTR_premature_start_codon_gain_variant', '5_prime_UTR_variant'].indexOf(val) > -1) {
                                targets.push('Intronic/<br>Non-coding');
                                return false;
                            }
                            else if (['intergenic_region'].indexOf(val) > -1) {
                                targets.push('Intergenic');
                                return false;
                            }
                        }, this);

                        if (!targets.length){
                            targets.push(values);
                        }

                        $.each(targets, function(idx, target){
                            codingDataMap[target] = codingDataMap[target] || 0;
                            codingDataMap[target] += data1[values];
                        }, this);
                    }

                    $.each(codingLabels, function(idx, val){
                        codingData.push(codingDataMap[val] || 0);
                    });

                    Plotly.newPlot(targetId1, [{
                            "autobinx": true,
                            "uid": "13ab10",
                            "name": "B",
                            "labels": codingLabels,
                            "values": codingData,
                            "mode": "markers",
                            "textinfo": "label+percent",
                            "type": "pie",
                            "autobiny": true
                        }], {
                            "width": width,
                            "height": 300,
                            "margin": {"l": 80, "r": 0, "t": 0, "b": 0},
                            "autosize": false,
                            "showlegend": false,
                            "breakpoints": [],
                            "titlefont": {"size": 14},
                            "hovermode": "closest",
                            "font": {"size": 11},
                            "legend": {"font": {"size": 10}}
                        }, {displayModeBar: false});

                    var data2 = map.AnnotationSummary || {};
                    var annotationData = [];
                    annotationData.push(data2['GWAS Associations (GRASP)'] || 0);
                    annotationData.push(data2['Enhancer Region (FANTOM5)'] || 0);
                    annotationData.push(data2['Predicted Enhancer (ENCODE)'] || 0);
                    annotationData.push(data2['Transcription Factor Binding (ENCODE)'] || 0);
                    annotationData.push(data2['Predicted High Impact (SnpEff)'] || 0);
                    annotationData.push(data2['Damaging (Polyphen2)'] || 0);
                    annotationData.push(data2['ClinVar Overlap'] || 0);
                    Plotly.newPlot(targetId2, [{
                        "autobinx": true,
                        "name": "# Variants",
                        "mode": "markers",
                        "x": annotationData,
                        "y": ["GWAS Associations<br>(GRASP)", "Enhancer Region<br>(FANTOM5)", "Predicted Enhancer<br>(ENCODE)", "Transcription Factor<br>Binding (ENCODE)", "Predicted High<br>Impact (SnpEff)", "Damaging<br>(Polyphen2)", "ClinVar Overlap"],
                        "type": "bar",
                        "orientation": "h",
                        "autobiny": true
                    }], {
                        //"title": "Summary of Annotations",
                        "autosize": false,
                        "width": width,
                        "height": 400,
                        "margin": {"l": 150, "r": 0, "t": 0, "b": 40},
                        "breakpoints": [],
                        "hovermode": "closest",
                        "yaxis": {"tickfont": {"size": 12}, "title": "", "range": [-0.5, 6.5], "titlefont": {"size": 12}, "type": "category", "autorange": true},
                        "xaxis": {"tickfont": {"size": 12}, "title": "# Variants", "range": [0, 149672.63157894736], "titlefont": {"size": 12}, "type": "linear", "autorange": true}},
                    {displayModeBar: false});

                    var data3 = map.VariantType || {};
                    var variantTypeLabels = ['SNP', 'INDEL', 'MIXED'];
                    var variantTypeData = [];
                    var data3Total = 0;
                    $.each(variantTypeLabels, function(idx, val){
                        variantTypeData.push(data3[val] || 0);
                        data3Total += data3[val] || 0;
                    });

                    var variantTypeDataPct = [];
                    $.each(variantTypeLabels, function(idx, val){
                        variantTypeDataPct.push(variantTypeData[idx] / data3Total);
                    });

                    var dataFinal = [];
                    $.each(variantTypeLabels, function(idx, val){
                        dataFinal.push({
                            type: 'bar',
                            name: val,
                            x: [variantTypeDataPct[idx]],
                            text: [val],
                            textposition: 'auto',
                            orientation: 'h',
                            hoverinfo: 'x+text'
                        })
                    });

                    Plotly.newPlot(targetId3, dataFinal, {
                        "width": width,
                        "height": 100,
                        "margin": {"l": 20, "r": 20, "t": 20, "b": 20},
                        "autosize": false,
                        "showlegend": false,
                        "breakpoints": [],
                        "titlefont": {"size": 14},
                        "hovermode": "closest",
                        "font": {"size": 12},
                        "legend": {"font": {"size": 12}},
                        "xaxis": {visible: true, hoverformat: ',.1%', tickformat: ',.0%'},
                        "yaxis": {visible: false},
                        barmode: 'stack'
                    }, {displayModeBar: false});
                }
            })
        },

        getMGapJBrowseSession: function(){
            var ctx = LABKEY.getModuleContext('mgap') || {};

            return ctx['mgapJBrowse'];
        },

        getHumanMGapJBrowseSession: function(){
            var ctx = LABKEY.getModuleContext('mgap') || {};

            return ctx['mgapJBrowseHuman'];
        },

        getMGapReleaseId: function(){
            var ctx = LABKEY.getModuleContext('mgap') || {};

            return ctx['mgapReleaseId'];
        },

        getMGapReleaseVersion: function(){
            var ctx = LABKEY.getModuleContext('mgap') || {};

            return ctx['mgapReleaseVersion'];
        }
    }
})(jQuery);