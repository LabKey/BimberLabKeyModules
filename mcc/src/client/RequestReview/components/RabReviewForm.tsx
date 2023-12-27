import { ActionURL, Filter, getServerContext, Query } from '@labkey/api';
import React, { useEffect, useState } from 'react';
import { Box, Button, MenuItem, Select, Table, TableBody, TableCell, TableRow, TextField } from '@mui/material';
import SavingOverlay from '../../AnimalRequest/saving-overlay';

export default function RabReviewForm(props: {requestId: string}) {
    const [ recordData, setRecordData ] = useState(null)
    const [displayOverlay, setDisplayOverlay] = useState(false)
    const [hasSubmitted, setHasSubmitted] = useState(false)

    useEffect(() => {
        Query.selectRows({
            schemaName: "mcc",
            queryName: "requestReviews",
            columns: [
                "rowid",
                "reviewerid",
                "reviewerid/displayName",
                "reviewerid/email",
                "role",
                "review",
                "score",
                "comments",
                "requestid"
            ],
            filterArray: [
                Filter.create('requestId', props.requestId),
                Filter.create('reviewerId', getServerContext().user.id)
            ],
            success: function (resp) {
                if (resp.rows.length > 1) {
                    console.error("More than one MCC review record returned for the user: " + getServerContext().user.id + ". this should never happen")
                }

                setRecordData([...resp.rows])
            },
            failure: function (response) {
                alert(response.exception)
            }
        })
    }, [props.requestId])

    if (!recordData) {
        return(<div>Loading...</div>)
    }

    if (!recordData?.length) {
        return(<div style={{paddingTop: 20}}>You have not been assigned to review this request</div>)
    }

    const handleChange = (e) => {
        recordData[0][e.target.name] = e.target.value ? e.target.value.trim() : null
        setRecordData([...recordData])
    };

    const onFormSubmit = (e: React.SyntheticEvent<HTMLFormElement>) => {
        setHasSubmitted(true)
        e.preventDefault()
        if (!e.currentTarget.reportValidity()) {
            return
        }

        setDisplayOverlay(true)
        const row = recordData[0]
        if (row.rowid) {
            Query.updateRows({
                schemaName: "mcc",
                queryName: "requestReviews",
                rows: [{
                    rowid: row.rowid,
                    review: row.review,
                    comments: row.comments
                }],
                success: function (resp) {
                    window.location.href = ActionURL.buildURL('mcc', 'rabRequestReview')
                },
                failure: function (response) {
                    setDisplayOverlay(false)
                    console.error(response)
                    alert(response.exception)
                }
            })
        }
        else {
            Query.insertRows({
                schemaName: "mcc",
                queryName: "requestReviews",
                rows: [{
                    review: row.review,
                    comments: row.comments
                }],
                success: function (resp) {
                    window.location.href = ActionURL.buildURL('mcc', 'rabRequestReview')
                },
                failure: function (response) {
                    setDisplayOverlay(false)
                    console.error(response)
                    alert(response.exception)
                }
            })
        }

    }

    return (
        <>
        <h2>Enter RAB Review</h2>
        <form noValidate autoComplete='off' onSubmit={onFormSubmit}>
        <Box style={{display: 'inline-block'}}>
            After reviewing the request, please fill out the section below and provide a 2-3 sentence justification for your choice.
            <Table width={500}>
            <TableBody>
            <TableRow>
                <TableCell>
                    <Select id={"review"} variant={"outlined"} name={"review"} aria-label="Review" label={"Review"} error={hasSubmitted && !recordData[0].review} onChange={handleChange} required={true} value={recordData[0].review ?? ''} fullWidth={true} displayEmpty={true} placeholder={"Enter review..."}>
                        <MenuItem value={""}>Not Decided</MenuItem>
                        <MenuItem value={"I recommend this proposal"}>I recommend this proposal</MenuItem>
                        <MenuItem value={"I recommend this proposal with conditions"}>I recommend this proposal with conditions</MenuItem>
                        <MenuItem value={"I do not recommend this proposal"}>I do not recommend this proposal</MenuItem>
                        <MenuItem value={"I abstain from voting"}>I abstain from voting</MenuItem>
                    </Select>
                </TableCell>
            </TableRow>
            <TableRow>
                <TableCell><TextField key={"comments"} name={"comments"} label={"Justification"} minRows={4} multiline={true} onChange={handleChange} variant={'outlined'} defaultValue={recordData[0].comments || ''} fullWidth={true} /></TableCell>
            </TableRow>
            </TableBody>
        </Table>
        <p />
        <Button variant={"contained"} style={{marginRight: 10}} type={'submit'}>Submit Review</Button>
        </Box>
        </form>
        <SavingOverlay display={displayOverlay} />
        </>
    )
}