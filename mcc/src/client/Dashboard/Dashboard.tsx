import './dashboard.css';

import React, { useState, useEffect } from 'react';
import { Query } from '@labkey/api';
import { getServerContext } from "@labkey/api";

import PieChart from './PieChart';
import BarChart from './BarChart';

export function Dashboard() {
    const [demographics, setDemographics] = useState(null);
    const [living, setLiving] = useState(null);

    const ctx = getServerContext().getModuleContext('mcc') || {};
    const containerPath = ctx.MCCContainer || null;
    if (!containerPath) {
        return (
            <div className="loading">
                <div>Error: must set the MCCContainer module property</div>
            </div>
        );
    }

    useEffect(() => {
        Query.selectRows({
                containerPath: containerPath,
                schemaName: 'study',
                queryName: 'demographics',
                columns: 'Id,birth,death,gender/meaning,species,colony,calculated_status,Id/age/AgeFriendly,Id/ageClass/label',
                success: function(results) {
                    setLiving(results.rows.filter(row => row.calculated_status === 'Alive'));
                    setDemographics(results.rows);
                },
                failure: function(response) {
                    alert('There was an error loading data');
                    console.log(response);
                },
                scope: this
            });
    }, [] /* only run the effect on mount */);

    if (demographics === null) {
        return (
            <div className="loading">
                <div>Loading...</div>
            </div>
        );
    }

    return (
        <>
            <div className="row">
                <div className="col-md-4">
                    <div className="panel panel-default">
                        <div className="panel-heading">Count</div>
                        <div className="panel-body count-panel-body">
                            <div className="count-panel-text">{demographics.length}</div>
                            <div className="small text-muted">Marmosets tracked by MCC</div>
                        </div>
                    </div>
                </div>
                <div className="col-md-4">
                    <div className="panel panel-default">
                        <div className="panel-heading">Center (All Animals)</div>
                        <div className="panel-body">
                            <PieChart fieldName = "colony" demographics={demographics} cutout = "30%" />
                        </div>
                    </div>
                </div>
            </div>
            <div className="row">
                <div className="col-md-4">
                    <div className="panel panel-default">
                        <div className="panel-heading">Age (Living Animals)</div>
                        <div className="panel-body">
                            <BarChart fieldName = "Id/ageClass/label" demographics={living} />
                        </div>
                    </div>
                </div>
                <div className="col-md-4">
                    <div className="panel panel-default">
                        <div className="panel-heading">Sex (Living Animals)</div>
                        <div className="panel-body">
                            <PieChart fieldName = "gender/meaning" demographics={living} />
                        </div>
                    </div>
                </div>
            </div>
        </>
    );
}