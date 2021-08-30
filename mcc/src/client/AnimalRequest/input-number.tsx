import React from 'react';

export default function InputNumber(props) {
    return (
        <input className="tw-appearance-none tw-inline tw-w-1/2 tw-text-gray-700 tw-border-gray-300 tw-rounded tw-py-4 tw-px-6 tw-mb-3 tw-leading-tight focus:tw-outline-none" 
         name={props.id} id={props.id} type="number" min="0" max="1000" required={props.required}/>
    )
}
