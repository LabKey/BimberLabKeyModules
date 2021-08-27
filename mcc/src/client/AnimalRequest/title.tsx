import React from 'react';

export default function Title(props) {
    return (
        <p className="tw-w-full tw-inline tw-uppercase tw-tracking-wide tw-text-gray-700 tw-text-md tw-font-bold tw-mb-6 tw-bg-transparent tw-border-none">{props.text}</p>
    )
}