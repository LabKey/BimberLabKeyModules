import React, { useState, useEffect, FormEvent } from 'react';
import { Query } from '@labkey/api';

import Tooltip from './tooltip'
import Title from './title'
import Input from './input'
import Select from './select'
import CoInvestigators from './co-investigators'
import TextArea from './text-area'
import YesNoRadio from './yes-no-radio'
import InputNumber from './input-number'
import ResearchArea from './research-area'

import {
    earlyInvestigatorTooltip, institutionTypeOptions, 
    isPrincipalInvestigatorOptions, fundingSourceOptions,
    experimentalRationalePlaceholder, otherCharacteristicsPlaceholder,
    methodsProposedPlaceholder, collaborationsPlaceholder,
    ofInterestCentersPlaceholder, animalWellfarePlaceholder,
    certificationLabel, IACUCApprovalOptions
} from './values'


function handleSubmit(e: FormEvent) {
    e.preventDefault();

    const data = new FormData(e.currentTarget)

    console.log(data.get("investigator-last-name"))
    console.log(data.get("investigator-first-name"))
    console.log(data.get("investigator-middle-initial"))

    console.log(data.get("is-principal-investigator"))

    console.log(data.get("institution-name"))
    console.log(data.get("institution-city"))
    console.log(data.get("institution-state"))
    console.log(data.get("institution-country"))

    console.log(data.get("institution-type"))
    
    console.log(data.get("official-last-name"))
    console.log(data.get("official-first-name"))
    console.log(data.get("official-email"))

    console.log(data.get("coinvestigators-0-lastName"))

    console.log(data.get("funding-source"))

    console.log(data.get("experiment-rationale"))
    console.log(data.get("number-of-animals"))
    console.log(data.get("other-characteristics"))
    console.log(data.get("methods-proposed"))
    console.log(data.get("collaborations"))
    console.log(data.get("is-planning-to-breed-animals"))
    console.log(data.get("of-interest-centers"))

    console.log(data.get("research-area-other-specify"))
    console.log(data.get("existing-marmoset-colony"))
    console.log(data.get("existing-nhp-facilities"))

    console.log(data.get("animal-welfare"))
    console.log(data.get("certify"))


    console.log(data.get("vet-last-name"))
    console.log(data.get("vet-first-name"))
    console.log(data.get("vet-email"))

    console.log(data.get("iacuc-approval"))

    alert("Your request was submitted.")
}

export function AnimalRequest() {
     return (
        <form className="tw-w-full tw-max-w-4xl" onSubmit={handleSubmit}>
            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="1. Principal Investigator*"/>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="investigator-last-name" required={true} placeholder="Last Name"/>
                </div>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="investigator-first-name" required={true} placeholder="First Name"/>
                </div>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="investigator-middle-initial" required={true} placeholder="Middle Initial"/>
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <div className="tw-relative tw-w-full tw-mb-6 md:tw-mb-0">
                    <Title text="2. Are you an early-stage investigator?&nbsp;"/>
                    <Tooltip id="early-stage-investigator-helper"
                       text={earlyInvestigatorTooltip}
                    />
                </div>


                <div className="tw-w-full tw-px-3 tw-mt-6">
                    <YesNoRadio id="is-principal-investigator" required={true}/>
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="3. Affiliated research institution*"/>

                <div className="tw-w-full tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="institution-name" placeholder="Name" required={true}/>
                </div>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="institution-city" placeholder="City" required={true}/>
                </div>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="institution-state" placeholder="State" required={true}/>
                </div>

                <div className="tw-w-full md:tw-w-1/3 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="institution-country" placeholder="Country" required={true}/>
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="4. Institution Type*"/>

                <div className="tw-w-full tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Select id="institution-type" options={institutionTypeOptions} />
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="5. Institution Signing Official*"/>

                <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="official-last-name" placeholder="Last Name" required={true}/>
                </div>

                <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="official-first-name" placeholder="First Name" required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Input id="official-email" placeholder="Email Address" required={true}/>
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="6. Co-investigators"/>

                <CoInvestigators />
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                <Title text="7. Existing or proposed funding source*"/>

                <div className="tw-w-full tw-px-3 tw-mb-6 md:tw-mb-0">
                    <Select id="funding-source" options={fundingSourceOptions} required={true}/>
                </div>
            </div>
             
            <div className="tw-flex tw-flex-wrap tw-mx-2">
                <Title text="8. Research Use Statement*"/>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <TextArea id="experiment-rationale" placeholder={experimentalRationalePlaceholder} required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <Title text="Number of animals needed:&nbsp;&nbsp;&nbsp;&nbsp;"/>
                    <InputNumber id="number-of-animals" required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <TextArea id="other-characteristics" placeholder={otherCharacteristicsPlaceholder} required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <TextArea id="methods-proposed" placeholder={methodsProposedPlaceholder} required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <TextArea id="collaborations" placeholder={collaborationsPlaceholder} required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6 tw-mt-6">
                    <div className="tw-mb-6">
                        <Title text="Do you plan to breed animals?"/>
                    </div>

                    <div className="tw-mb-6">
                        <YesNoRadio id="is-planning-to-breed-animals" required={true}/>
                    </div>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <TextArea id="of-interest-centers" placeholder={ofInterestCentersPlaceholder} required={true}/>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <div className="tw-mb-6">
                        <Title text="Research Area"/>
                    </div>
                    <ResearchArea id="research-area" />
                </div>

                <div className="tw-w-full tw-px-3">
                    <div className="tw-mb-6">
                        <Title text="Animal Facilities and Capabilities"/>
                    </div>

                    <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                        <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                            <input type="radio" name="existing-marmoset-colony" value="existing" required/>
                            <label className="tw-text-gray-700 ml-1">Existing marmoset colony</label>
                        </div>

                        <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                            <input type="radio" name="existing-marmoset-colony" value="not-existing" required/>
                            <label className="tw-text-gray-700 ml-1">No existing marmoset colony</label>
                        </div>
                    </div>

                    <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                        <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                            <input type="radio" name="existing-nhp-facilities" value="existing" required/>
                            <label className="tw-text-gray-700 ml-1">Existing NHP facilities</label>
                        </div>

                        <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                            <input type="radio" name="existing-nhp-facilities" value="not-existing" required/>
                            <label className="tw-text-gray-700 ml-1">No existing NHP facilities</label>
                        </div>
                    </div>
                </div>

                <div className="tw-w-full tw-px-3 tw-mb-6">
                    <div className="tw-w-full tw-px-3 tw-mb-6">
                        <TextArea id="animal-welfare" placeholder={animalWellfarePlaceholder} required={true}/>
                    </div>

                    <div className="tw-w-full tw-px-3 tw-mb-6">
                        <input type="checkbox" name="certify" required={true}/>
                        <label className="tw-text-gray-700 ml-1">{certificationLabel}</label>
                    </div>
                </div>

                <div className="tw-flex tw-flex-wrap tw-mx-2 tw-mb-10">
                    <Title text="Attending veterinarian"/>

                    <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                        <Input id="vet-last-name" placeholder="Last Name" required={true}/>
                    </div>

                    <div className="tw-w-full md:tw-w-1/2 tw-px-3 tw-mb-6 md:tw-mb-0">
                        <Input id="vet-first-name" placeholder="First Name" required={true}/>
                    </div>

                    <div className="tw-w-full tw-px-3 tw-mb-6 md:tw-mb-0">
                        <Input id="vet-email" placeholder="Email Address" required={true}/>
                    </div>
                </div>

                <div className="tw-flex tw-flex-wrap tw-mx-2">
                    <Title text="IACUC Approval"/>

                    <div className="tw-w-full tw-px-3 md:tw-mb-0">
                        <Select id="iacuc-approval" options={IACUCApprovalOptions} required={true}/>
                    </div>
                </div>
            </div>

            <div className="tw-flex tw-flex-wrap tw-mx-2">
                <button className="tw-ml-auto tw-bg-blue-500 hover:tw-bg-blue-400 tw-text-white tw-font-bold tw-py-4 tw-mt-2 tw-px-6 tw-border-none tw-rounded">Submit</button>
            </div>
        </form>
     )
}
