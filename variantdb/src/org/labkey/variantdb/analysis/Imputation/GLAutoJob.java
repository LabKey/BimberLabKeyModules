package org.labkey.variantdb.analysis.Imputation;

import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.util.Job;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.run.GLAutoRunner;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Created by bimber on 5/9/2016.
 */
public class GLAutoJob extends Job
{
    private int _mcIterations = 300000;
    private int _mcStoreInterval = 30;

    private final File _basedir;
    private final File _markerFile;
    private final Logger _log;
    private final String _chr;

    public GLAutoJob(File basedir, File markerFile, Logger log, String chr)
    {
        _basedir = basedir;
        _markerFile = markerFile;
        _log = log;
        _chr = chr;
    }

    @Override
    public void run()
    {
        try
        {
            File seedFile = new File(_basedir, "sampler.seed");
            try (PrintWriter seedWriter = PrintWriters.getPrintWriter(seedFile))
            {
                seedWriter.write("set sampler seeds  0xb69cb2f5 0x562302c9\n");
            }

            File glAutoParams = new File(_basedir, "glauto.par");
            try (PrintWriter glautoWriter = PrintWriters.getPrintWriter(glAutoParams))
            {
                glautoWriter.write("input pedigree file '../../morgan.ped'\n");
                glautoWriter.write("input marker data file '" + _markerFile.getName() + "'\n");
                glautoWriter.write("input seed file 'sampler.seed'\n\n");

                glautoWriter.write("#output file:\n");
                glautoWriter.write("output extra file 'framework.IVs'\n");
                glautoWriter.write("output meiosis indicators\n\n");

                glautoWriter.write("# Take care of pedigree order issues\n");
                glautoWriter.write("output pedigree file 'ordered.ped'\n\n");

                glautoWriter.write("check markers consistency\n\n");

                glautoWriter.write("########## other gl_auto program options #############\n");
                glautoWriter.write("# scoring:  We keep MCMC samples that are less correlated (every " + _mcStoreInterval + "th).\n");
                glautoWriter.write("output scores every " + _mcStoreInterval + " scored MC iterations   # these are the realized IVs - In this example, we will print 300/30 = 100 IVs to the output file\n");
                glautoWriter.write("set MC iterations " + _mcIterations + "\n");
                glautoWriter.write("set burn-in iterations 1000\n");
                glautoWriter.write("check progress 1000 MC iterations\n");

                glautoWriter.write("select all markers\n");
                glautoWriter.write("select trait 1\n");

                glautoWriter.write("use multiple meiosis sampler\n");
                glautoWriter.write("set limit for exact computation 12\n");

                glautoWriter.write("# Monte Carlo setup and requests\n");
                glautoWriter.write("use sequential imputation for setup\n");
                glautoWriter.write("sample by scan\n");
                glautoWriter.write("set L-sampler probability 0.5\n\n");

                glautoWriter.write("##################################################################\n");
                glautoWriter.write("#these dummy lines are here just so the program will run\n");
                glautoWriter.write("#the inference of IVs doesn't have anything to do with the trait\n");
                glautoWriter.write("#just include these lines:\n");
                glautoWriter.write("#trait\n");
                glautoWriter.write("#tloc 11 is just a name\n");
                glautoWriter.write("set trait 1  tloc 11\n");
                glautoWriter.write("map tloc 11 unlinked   # trait locus is unlinked\n");
                glautoWriter.write("set tloc 11 allele freqs 0.5 0.5\n");
            }

            File orderedPed = new File(_basedir, "ordered.ped");
            if (orderedPed.exists())
            {
                orderedPed.delete();

            }
            GLAutoRunner runner = new GLAutoRunner(_log);
            runner.setWorkingDir(_basedir);
            runner.execute(glAutoParams);
        }
        catch (PipelineJobException | IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void done(Throwable t)
    {
        if (null != t)
            _log.error("Uncaught exception in GL_AUTO job: " + _chr, t);
    }

    public int getMcIterations()
    {
        return _mcIterations;
    }

    public void setMcIterations(int mcIterations)
    {
        _mcIterations = mcIterations;
    }

    public int getMcStoreInterval()
    {
        return _mcStoreInterval;
    }

    public void setMcStoreInterval(int mcStoreInterval)
    {
        _mcStoreInterval = mcStoreInterval;
    }
}
