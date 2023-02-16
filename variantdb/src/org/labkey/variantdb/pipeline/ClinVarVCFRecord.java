package org.labkey.variantdb.pipeline;

import htsjdk.variant.variantcontext.VariantContext;

/**
 * Created by bimber on 1/7/2015.
 *
 * Designed to help parsing of Clinvar VCF attributes.
 * See here for more documention:
 * http://www.ncbi.nlm.nih.gov/variation/docs/faq/
 *
 */
public class ClinVarVCFRecord
{
    private final VariantContext _ctx;

    public ClinVarVCFRecord(VariantContext ctx)
    {
        _ctx = ctx;
    }
}
