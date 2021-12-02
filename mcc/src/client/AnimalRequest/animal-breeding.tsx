import React, { useState } from 'react';

import YesNoRadio from './yes-no-radio'
import TextArea from './text-area'

import { animalBreedingPlaceholder } from './values'

export default function AnimalBreeding(props) {

    const [displayPurpose, setDisplayPurpose] = useState(false);

    function setDisplayPurposeField(value) {
        if(value == "yes") {
            setDisplayPurpose(true)
        } else {
            setDisplayPurpose(false)
        }
    }

    return (
        <>
            <div className="tw-mb-6">
                <YesNoRadio id={props.id + "-is-planning-to-breed-animals"} required={true} defaultValue={props.defaultValue.isbreedinganimals} 
                 onChange={(e) => setDisplayPurposeField(e.currentTarget.value)}/>
            </div>

            <div className="tw-mb-6">
                <TextArea id={props.id + "-purpose"} isSubmitting={props.isSubmitting} placeholder={animalBreedingPlaceholder}
                    display={displayPurpose} required={displayPurpose} defaultValue={props.defaultValue.breedingpurpose}/>
            </div>
        </>
    )
}
