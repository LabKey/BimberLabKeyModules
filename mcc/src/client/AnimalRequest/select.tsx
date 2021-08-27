import React from 'react';

export default function Select(props) {
    return (
        <select className="tw-block tw-border tw-border-gray-300 tw-text-gray-700 tw-py-3 tw-pr-8 tw-rounded tw-leading-tight focus:tw-outline-none" defaultValue="none"
                name={props.id} id={props.id} required={props.required} onChange={props.onChange}>

            <option disabled value="none"> -- </option>
            {props.options.map(({ value, label }, _) => <option key={value} value={value}>{label}</option>)}
        </select>
    )
}
